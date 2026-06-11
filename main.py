import os
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

def get_daily_summary():
    auth = Auth.Token(GITHUB_TOKEN)
    g = Github(auth=auth)
    now = datetime.datetime.now(datetime.timezone.utc)
    since = now - datetime.timedelta(days=1)
    
    summary_data = []
    
    # Parse repo list
    repo_names = [r.strip() for r in GITHUB_REPOS.split(",") if r.strip()]
    
    if not repo_names:
        repos = g.get_user().get_repos(sort="updated", direction="desc")
    else:
        repos = [g.get_repo(name) for name in repo_names]

    for repo in repos:
        # Check if repo was updated in last 24h
        if repo.updated_at < since:
            if not repo_names:
                break
            continue
            
        repo_summary = {
            "name": repo.full_name,
            "commits": [],
            "prs": [],
            "issues": []
        }
        
        # Fetch Commits from 'dev' branch
        try:
            # Try to get commits from 'dev' branch specifically
            commits = repo.get_commits(sha='dev', since=since)
            for commit in commits:
                msg = commit.commit.message.split('\n')[0]
                # Filter out merge commits if they are too noisy
                if msg.startswith("Merge pull request") or msg.startswith("Merge branch"):
                    continue
                repo_summary["commits"].append({
                    "msg": msg,
                    "author": commit.commit.author.name,
                    "url": commit.html_url
                })
        except Exception:
            # Fallback to default branch if 'dev' doesn't exist
            try:
                commits = repo.get_commits(since=since)
                for commit in commits:
                    msg = commit.commit.message.split('\n')[0]
                    if msg.startswith("Merge pull request") or msg.startswith("Merge branch"):
                        continue
                    repo_summary["commits"].append({
                        "msg": msg,
                        "author": commit.commit.author.name,
                        "url": commit.html_url
                    })
            except Exception:
                pass
            
        # Fetch PRs (opened or updated)
        pulls = repo.get_pulls(state='all', sort='updated', direction='desc')
        for pr in pulls:
            if pr.updated_at < since:
                break
            # Skip the bot's own PR to reduce noise
            if "github-slack-summary-bot" in pr.head.ref:
                continue
            repo_summary["prs"].append({
                "title": pr.title,
                "state": pr.state,
                "url": pr.html_url,
                "number": pr.number
            })
            
        # Fetch Issues (opened or updated)
        issues = repo.get_issues(state='all', since=since)
        for issue in issues:
            if issue.pull_request:
                continue
            repo_summary["issues"].append({
                "title": issue.title,
                "state": issue.state,
                "url": issue.html_url,
                "number": issue.number
            })
            
        if repo_summary["commits"] or repo_summary["prs"] or repo_summary["issues"]:
            summary_data.append(repo_summary)
            
    return summary_data

def _clip(text, limit=2900):
    """Slack section text는 3000자 한도. 초과 시 잘라서 안내."""
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "\n…(길어서 일부 생략)"

def format_slack_message(summary_data):
    if not summary_data:
        return []

    today = datetime.datetime.now().strftime('%Y-%m-%d')
    total_commits = sum(len(r["commits"]) for r in summary_data)
    total_prs = sum(len(r["prs"]) for r in summary_data)
    total_open = sum(len([p for p in r["prs"] if p["state"] == "open"]) for r in summary_data)
    total_issues = sum(len(r["issues"]) for r in summary_data)

    blocks = [
        {"type": "header", "text": {"type": "plain_text",
            "text": f"🚀 Bifrost 일일 작업 리포트 ({today})", "emoji": True}},
        {"type": "section", "text": {"type": "mrkdwn",
            "text": f"📊 *오늘 작업량* — 커밋 *{total_commits}* · PR *{total_prs}* (열림 {total_open}) · 이슈 *{total_issues}*"}},
        {"type": "divider"},
    ]

    for repo in summary_data:
        commits = repo["commits"]
        open_prs = [p for p in repo["prs"] if p["state"] == "open"]
        issues = repo["issues"]
        L = [f"*📍 <https://github.com/{repo['name']}|{repo['name']}>*  ·  커밋 {len(commits)} · PR {len(repo['prs'])}(열림 {len(open_prs)}) · 이슈 {len(issues)}"]
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
