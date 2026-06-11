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

def format_slack_message(summary_data):
    blocks = [
        {
            "type": "header",
            "text": {
                "type": "plain_text",
                "text": "🚀 Bifrost 프로젝트 일일 작업 리포트",
                "emoji": True
            }
        },
        {
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": f"*생성 일시:* {datetime.datetime.now().strftime('%Y-%m-%d %H:%M')}\n*대상 브랜치:* `dev`"
            }
        },
        {"type": "divider"}
    ]
    
    if not summary_data:
        blocks.append({
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": "✅ 지난 24시간 동안 새로운 작업 내역이 없습니다."
            }
        })
        return blocks

    for repo in summary_data:
        # Header for the repository
        blocks.append({
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": f"*📍 <https://github.com/{repo['name']}|{repo['name']}>*"
            }
        })

        # 1. New Code (Commits)
        if repo["commits"]:
            commit_text = "📝 *주요 변경 사항 (Commits)*\n"
            for c in repo["commits"][:10]:
                commit_text += f"• {c['msg']} (@{c['author']})\n"
            if len(repo["commits"]) > 10:
                commit_text += f"• _외 {len(repo['commits'])-10}개의 커밋 더보기..._\n"
            
            blocks.append({
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": commit_text
                }
            })

        # 2. Pull Requests
        if repo["prs"]:
            pr_text = "📂 *진행 중인 작업 (Pull Requests)*\n"
            for pr in repo["prs"]:
                status = "✅ [Merged/Closed]" if pr["state"] == "closed" else "🏗️ [Open]"
                pr_text += f"• {status} <{pr['url']}|#{pr['number']} {pr['title']}>\n"
            
            blocks.append({
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": pr_text
                }
            })

        # 3. Issues
        if repo["issues"]:
            issue_text = "🚩 *이슈 및 알림 (Issues)*\n"
            for issue in repo["issues"]:
                status = "🟣 [Closed]" if issue["state"] == "closed" else "🔴 [Open]"
                issue_text += f"• {status} <{issue['url']}|#{issue['number']} {issue['title']}>\n"
            
            blocks.append({
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": issue_text
                }
            })

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
    print("Sending to Slack...")
    send_to_slack(slack_blocks)
