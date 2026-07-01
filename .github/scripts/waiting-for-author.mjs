const CONFIG = {
  apiUrl: process.env.GITHUB_API_URL || "https://api.github.com",
  repository: process.env.GITHUB_REPOSITORY,
  token: process.env.GITHUB_TOKEN,
  labelName: "waiting for author",
  firstPeriodDays: 7,
  secondPeriodDays: 14,
  reminderMessage:
    "This issue has been waiting for the original author for {firstPeriodDays} days. If there is no author activity by day {secondPeriodDays}, it will be closed automatically.",
  closingMessage:
    "Closing this issue because there has been no author activity for {secondPeriodDays} days while `{labelName}` was set. Comment with the requested details and it can be reopened.",
};

if (!CONFIG.repository) {
  throw new Error("Missing GITHUB_REPOSITORY");
}

if (!CONFIG.token) {
  throw new Error("Missing GITHUB_TOKEN");
}

const [owner, repo] = CONFIG.repository.split("/");
const now = new Date();
const commitLikeEvents = new Set([
  "committed",
  "referenced",
  "cross-referenced",
]);
const ignoredTimelineEvents = new Set([
  "labeled",
  "unlabeled",
  "assigned",
  "unassigned",
  "milestoned",
  "demilestoned",
]);

function addDays(date, days) {
  return new Date(date.getTime() + days * 24 * 60 * 60 * 1000);
}

function isAfter(dateString, referenceDate) {
  return new Date(dateString).getTime() > referenceDate.getTime();
}

function buildMessage(template) {
  return template.replace(/\{(\w+)\}/g, (match, key) => {
    if (!(key in CONFIG)) {
      return match;
    }

    return String(CONFIG[key]);
  });
}

async function callGithub(path, init = {}) {
  const response = await fetch(`${CONFIG.apiUrl}${path}`, {
    ...init,
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${CONFIG.token}`,
      "User-Agent": "waiting-for-author-workflow",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(init.headers || {}),
    },
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(
      `GitHub API ${response.status} ${response.statusText} for ${path}: ${body}`,
    );
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

async function paginate(path) {
  const results = [];
  let page = 1;

  while (true) {
    const separator = path.includes("?") ? "&" : "?";
    const pageResults = await callGithub(
      `${path}${separator}per_page=100&page=${page}`,
    );

    if (!Array.isArray(pageResults) || pageResults.length === 0) {
      break;
    }

    results.push(...pageResults);

    if (pageResults.length < 100) {
      break;
    }

    page += 1;
  }

  return results;
}

function hasTargetLabel(item) {
  return item.labels?.some((label) => label.name === CONFIG.labelName);
}

async function listOpenIssues() {
  const items = await paginate(
    `/repos/${owner}/${repo}/issues?state=open&sort=updated&direction=asc`,
  );

  return items.filter((item) => !item.pull_request);
}

async function listOpenPullRequests() {
  return paginate(
    `/repos/${owner}/${repo}/pulls?state=open&sort=updated&direction=asc`,
  );
}

async function getIssue(issueNumber) {
  return callGithub(`/repos/${owner}/${repo}/issues/${issueNumber}`);
}

async function listWaitingItems() {
  const [openIssues, openPullRequests] = await Promise.all([
    listOpenIssues(),
    listOpenPullRequests(),
  ]);

  const labeledIssues = openIssues.filter(hasTargetLabel);
  const labeledPullRequests = [];

  for (const pullRequest of openPullRequests) {
    const issue = await getIssue(pullRequest.number);

    if (hasTargetLabel(issue)) {
      labeledPullRequests.push(issue);
    }
  }

  console.log(
    `Scanned ${openIssues.length} open issues and ${openPullRequests.length} open pull requests`,
  );

  return [...labeledIssues, ...labeledPullRequests].sort(
    (left, right) =>
      new Date(left.updated_at).getTime() -
      new Date(right.updated_at).getTime(),
  );
}

async function listIssueComments(issueNumber) {
  return paginate(`/repos/${owner}/${repo}/issues/${issueNumber}/comments`);
}

async function listIssueTimeline(issueNumber) {
  return paginate(`/repos/${owner}/${repo}/issues/${issueNumber}/timeline`);
}

async function removeLabel(issueNumber) {
  try {
    await callGithub(
      `/repos/${owner}/${repo}/issues/${issueNumber}/labels/${encodeURIComponent(CONFIG.labelName)}`,
      { method: "DELETE" },
    );
  } catch (error) {
    if (String(error.message).includes("404")) {
      return;
    }
    throw error;
  }
}

async function createComment(issueNumber, body) {
  await callGithub(`/repos/${owner}/${repo}/issues/${issueNumber}/comments`, {
    method: "POST",
    body: JSON.stringify({ body }),
  });
}

async function closeIssue(issueNumber) {
  await callGithub(`/repos/${owner}/${repo}/issues/${issueNumber}`, {
    method: "PATCH",
    body: JSON.stringify({ state: "closed", state_reason: "not_planned" }),
  });
}

function getLastLabelEvent(timeline) {
  return timeline
    .filter(
      (event) =>
        event.event === "labeled" && event.label?.name === CONFIG.labelName,
    )
    .sort(
      (left, right) =>
        new Date(right.created_at).getTime() -
        new Date(left.created_at).getTime(),
    )[0];
}

function hasAuthorCommentSince(comments, authorLogin, labeledAt) {
  return comments.some(
    (comment) =>
      comment.user?.login === authorLogin &&
      isAfter(comment.created_at, labeledAt),
  );
}

function hasAuthorTimelineActivitySince(timeline, authorLogin, labeledAt) {
  return timeline.some((event) => {
    if (!isAfter(event.created_at, labeledAt)) {
      return false;
    }

    if (
      commitLikeEvents.has(event.event) &&
      event.actor?.login === authorLogin
    ) {
      return true;
    }

    if (ignoredTimelineEvents.has(event.event)) {
      return false;
    }

    if (event.event === "connected" || event.event === "cross-referenced") {
      return event.source?.issue?.user?.login === authorLogin;
    }

    return false;
  });
}

function commentAlreadyExists(comments, marker) {
  return comments.some((comment) => comment.body?.includes(marker));
}

async function processIssue(issue) {
  const itemType = issue.pull_request ? "pull request" : "issue";

  const timeline = await listIssueTimeline(issue.number);
  const labelEvent = getLastLabelEvent(timeline);

  if (!labelEvent) {
    console.log(
      `Skipping ${itemType} #${issue.number}: no labeled event found for "${CONFIG.labelName}"`,
    );
    return;
  }

  const comments = await listIssueComments(issue.number);
  const authorLogin = issue.user?.login;
  const labeledAt = new Date(labelEvent.created_at);
  const firstDeadline = addDays(labeledAt, CONFIG.firstPeriodDays);
  const secondDeadline = addDays(labeledAt, CONFIG.secondPeriodDays);
  const reminderMarker = `<!-- waiting-for-author-reminder:${labelEvent.created_at} -->`;
  const closingMarker = `<!-- waiting-for-author-close:${labelEvent.created_at} -->`;

  if (
    authorLogin &&
    (hasAuthorCommentSince(comments, authorLogin, labeledAt) ||
      hasAuthorTimelineActivitySince(timeline, authorLogin, labeledAt))
  ) {
    console.log(
      `Removing "${CONFIG.labelName}" from ${itemType} #${issue.number}`,
    );
    await removeLabel(issue.number);
    return;
  }

  if (now >= secondDeadline) {
    if (!commentAlreadyExists(comments, closingMarker)) {
      const closingBody = `${buildMessage(CONFIG.closingMessage)}\n\n${closingMarker}`;
      console.log(`Closing ${itemType} #${issue.number}`);
      await createComment(issue.number, closingBody);
    }

    await closeIssue(issue.number);
    return;
  }

  if (now >= firstDeadline && !commentAlreadyExists(comments, reminderMarker)) {
    const reminderBody = `${buildMessage(CONFIG.reminderMessage)}\n\n${reminderMarker}`;
    console.log(`Posting reminder to ${itemType} #${issue.number}`);
    await createComment(issue.number, reminderBody);
    return;
  }

  console.log(
    `Checked ${itemType} #${issue.number}: no action needed (labeled ${labeledAt.toISOString()}, reminder ${firstDeadline.toISOString()}, close ${secondDeadline.toISOString()})`,
  );
}

async function main() {
  console.log(
    `Started scanning open issues and pull requests in ${CONFIG.repository} for label "${CONFIG.labelName}"`,
  );

  const issues = await listWaitingItems();
  console.log(
    `Found ${issues.length} open issues or pull requests with the target label`,
  );

  for (const issue of issues) {
    await processIssue(issue);
  }
}

await main();
