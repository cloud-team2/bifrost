import os
import re
import datetime
from github import Github, Auth
from slack_sdk import WebClient
from slack_sdk.errors import SlackApiError
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

GITHUB_TOKEN = os.getenv("GITHUB_TOKEN")
SLACK_BOT_TOKEN = os.getenv("SLACK_BOT_TOKEN")
SLACK_CHANNEL_ID = os.getenv("SLACK_CHANNEL_ID")
GITHUB_REPOS = os.getenv("MONITOR_REPOS", "")

_TYPE_PRIORITY = {"feat": 0, "fix": 1, "refactor": 2, "perf": 3, "chore": 4, "docs": 5, "test": 6}


def get_daily_summary():
    auth = Auth.Token(GITHUB_TOKEN)
    g = Github(auth=auth)
    now = datetime.datetime.now(datetime.timezone.utc)
    since = now - datetime.timedelta(days=1)

    summary_data = []

    repo_names = [r.strip() for r in GITHUB_REPOS.split(",") if r.strip()]
    if not repo_names:
        repos = g.get_user().get_repos(sort="updated", direction="desc")
    else:
        repos = [g.get_repo(name) for name in repo_names]

    for repo in repos:
        if repo.updated_at < since:
            if not repo_names:
                break
            continue

        repo_summary = {
            "name": repo.full_name,
            "commits": [],
            "prs": [],
            "issues": [],
            "merged": [],
        }

        # Commits (last 24h) — 'develop' 우선, 없으면 기본 브랜치 폴백
        for sha in ("develop", None):
            try:
                commits = repo.get_commits(sha=sha, since=since) if sha else repo.get_commits(since=since)
                for commit in commits:
                    msg = commit.commit.message.split("\n")[0]
                    if msg.startswith("Merge pull request") or msg.startswith("Merge branch"):
                        continue
                    repo_summary["commits"].append({
                        "msg": msg,
                        "author": commit.commit.author.name,
                        "login": commit.author.login if commit.author else None,
                        "url": commit.html_url,
                    })
                break
            except Exception:
                continue

        # PRs — 진행 중(open) 표시용 + 머지(merged) 순위용
        for pr in repo.get_pulls(state="all", sort="updated", direction="desc"):
            if pr.updated_at < since:
                break
            if "github-slack-summary-bot" in (pr.head.ref or ""):
                continue
            if pr.state == "open":
                repo_summary["prs"].append({
                    "title": pr.title, "state": "open", "url": pr.html_url, "number": pr.number,
                })
            if pr.merged_at and pr.merged_at >= since:
                repo_summary["merged"].append({
                    "title": pr.title, "number": pr.number,
                    "login": pr.user.login if pr.user else None,
                    "url": pr.html_url,
                })

        # Issues (last 24h)
        for issue in repo.get_issues(state="all", since=since):
            if issue.pull_request:
                continue
            repo_summary["issues"].append({
                "title": issue.title, "state": issue.state, "url": issue.html_url, "number": issue.number,
            })

        if repo_summary["commits"] or repo_summary["prs"] or repo_summary["issues"] or repo_summary["merged"]:
            summary_data.append(repo_summary)

    return summary_data


def _clip(text, limit=2900):
    """Slack section text는 3000자 한도. 초과 시 잘라서 안내."""
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "\n…(길어서 일부 생략)"


def _is_bot(login):
    return (not login) or ("[bot]" in login) or login == "bifrost-ci" or login.startswith("github-")


def _best_pr(prs):
    """우선순위(feat>fix>…) + 최신 번호 기준 대표 작업."""
    if not prs:
        return None
    def key(p):
        m = re.search(r"\[(\w+)\]", p["title"])
        return (_TYPE_PRIORITY.get(m.group(1) if m else "", 9), -p["number"])
    return sorted(prs, key=key)[0]


def format_slack_message(summary_data):
    if not summary_data:
        return []

    today = datetime.datetime.now().strftime("%Y-%m-%d")
    all_merged = [m for r in summary_data for m in r["merged"]]
    total_merged = len(all_merged)
    total_open = sum(len([p for p in r["prs"] if p["state"] == "open"]) for r in summary_data)
    total_issues = sum(len(r["issues"]) for r in summary_data)
    feat_n = sum(1 for m in all_merged if re.search(r"\[feat\]", m["title"]))
    fix_n = sum(1 for m in all_merged if re.search(r"\[fix\]", m["title"]))

    # 인당 집계(봇 제외): 머지 PR + 커밋
    stat = {}
    for m in all_merged:
        if _is_bot(m["login"]):
            continue
        stat.setdefault(m["login"], {"merged": [], "commits": 0})["merged"].append(m)
    for r in summary_data:
        for c in r["commits"]:
            lg = c["login"]
            if _is_bot(lg):
                continue
            stat.setdefault(lg, {"merged": [], "commits": 0})["commits"] += 1
    ranking = sorted(stat.items(), key=lambda kv: (-len(kv[1]["merged"]), -kv[1]["commits"], kv[0]))[:5]

    blocks = [
        {"type": "header", "text": {"type": "plain_text",
            "text": f"🚀 Bifrost 일일 작업 리포트 ({today})", "emoji": True}},
        {"type": "section", "text": {"type": "mrkdwn",
            "text": f"📊 *오늘 작업량* — 머지 PR *{total_merged}* · 열린 PR *{total_open}* · 이슈 *{total_issues}*"}},
    ]

    # 🏆 순위 & 베스트
    if ranking:
        medals = ["🥇 1등", "🥈 2등", "🥉 3등", "4등", "5등"]
        L = ["*🏆 오늘의 작업 순위 & 베스트*"]
        for i, (login, s) in enumerate(ranking):
            b = _best_pr(s["merged"])
            best = f"<{b['url']}|#{b['number']} {b['title']}>" if b else "—"
            L.append(f"{medals[i]}  *{login}*  (머지 PR {len(s['merged'])} · 커밋 {s['commits']})")
            L.append(f"    ⭐ {best}")
        blocks.append({"type": "section", "text": {"type": "mrkdwn", "text": _clip("\n".join(L))}})
    blocks.append({"type": "divider"})

    # 레포별 상세(요약형)
    for repo in summary_data:
        commits = repo["commits"]
        open_prs = [p for p in repo["prs"] if p["state"] == "open"]
        issues = repo["issues"]
        L = [f"*📍 <https://github.com/{repo['name']}|{repo['name']}>*  ·  머지 PR {len(repo['merged'])} · 커밋 {len(commits)} · 열린 PR {len(open_prs)} · 이슈 {len(issues)}"]
        if commits:
            L.append("\n✅ *반영된 작업*")
            for c in commits[:5]:
                L.append(f"• {c['msg']} (@{c['author']})")
            if len(commits) > 5:
                L.append(f"• _외 {len(commits)-5}개_")
        if open_prs:
            L.append("\n📂 *진행 중 PR*")
            for pr in open_prs[:5]:
                L.append(f"• <{pr['url']}|#{pr['number']} {pr['title']}>")
            if len(open_prs) > 5:
                L.append(f"• _외 {len(open_prs)-5}개_")
        if issues:
            L.append("\n🚩 *이슈*")
            for issue in issues[:5]:
                status = "🟣" if issue["state"] == "closed" else "🔴"
                L.append(f"• {status} <{issue['url']}|#{issue['number']} {issue['title']}>")
            if len(issues) > 5:
                L.append(f"• _외 {len(issues)-5}개_")
        blocks.append({"type": "section", "text": {"type": "mrkdwn", "text": _clip("\n".join(L))}})
        blocks.append({"type": "divider"})

    # 🧾 최하단 전체 요약(1~2문장)
    top = f"*{ranking[0][0]}* 님이 머지 PR {len(ranking[0][1]['merged'])}건으로 가장 활발했습니다. " if ranking else ""
    blocks.append({"type": "section", "text": {"type": "mrkdwn",
        "text": f"🧾 *오늘 정리* — 머지 PR {total_merged}건·이슈 {total_issues}건이 처리됐습니다. {top}기능 {feat_n}건·수정 {fix_n}건 반영."}})

    return blocks


def send_to_slack(blocks):
    client = WebClient(token=SLACK_BOT_TOKEN)
    try:
        response = client.chat_postMessage(
            channel=SLACK_CHANNEL_ID,
            blocks=blocks,
            text="오늘의 깃허브 작업 요약이 도착했습니다!"
        )
        print(f"Message sent: {response['ts']}")
    except SlackApiError as e:
        print(f"Error sending message: {e}")


if __name__ == "__main__":
    print("Fetching activity...")
    data = get_daily_summary()
    print("Formatting message...")
    slack_blocks = format_slack_message(data)
    if slack_blocks:
        print("Sending to Slack...")
        send_to_slack(slack_blocks)
    else:
        print("No activity found. Skipping Slack notification.")
