import * as cheerio from "cheerio";
import { Parser } from "commonmark";
import { readFile } from "node:fs/promises";

const CONFIG = {
  apiUrl: process.env.GITHUB_API_URL || "https://api.github.com",
  repository: process.env.GITHUB_REPOSITORY,
  token: process.env.GITHUB_TOKEN,
  targetKind: process.env.TARGET_KIND,
  targetNumber: process.env.TARGET_NUMBER,
  targetCommentId: process.env.TARGET_COMMENT_ID,
  targetReviewId: process.env.TARGET_REVIEW_ID,
  width: "400",
  localBodyFile: process.env.LOCAL_BODY_FILE,
};

const MARKDOWN_IMAGE_PATTERN =
  /!\[([^\]\n]*)\]\((<[^>\n]+>|[^)\s]+)(?:\s+(?:"([^"\n]*)"|'([^'\n]*)'))?\)/g;
const INLINE_CODE_SPAN_PATTERN = /`+[^`\n]*`+/g;

function buildImageHtml({ src, alt, title }) {
  const $ = cheerio.load("", null, false);
  const image = $("<img />");

  image.attr("src", src);
  image.attr("alt", alt || "");
  image.attr("width", CONFIG.width);

  if (title) {
    image.attr("title", title);
  }

  return $.html(image);
}

function rewriteHtmlFragment(fragment) {
  const $ = cheerio.load(fragment, null, false);
  let changed = false;

  $("img").each((_, element) => {
    const image = $(element);

    if (image.attr("width") !== CONFIG.width) {
      image.attr("width", CONFIG.width);
      changed = true;
    }

    if (image.attr("height") !== undefined) {
      image.removeAttr("height");
      changed = true;
    }
  });

  if (!changed) {
    return fragment;
  }

  return $.root().html() || fragment;
}

function getLineStartOffsets(input) {
  const offsets = [0];

  for (let index = 0; index < input.length; index += 1) {
    if (input[index] === "\n") {
      offsets.push(index + 1);
    }
  }

  return offsets;
}

function toOffset(lineStarts, position) {
  const [line, column] = position;
  return lineStarts[line - 1] + column - 1;
}

function isOffsetInRanges(offset, ranges) {
  return ranges.some((range) => offset >= range.start && offset < range.end);
}

function getProtectedRanges(body) {
  INLINE_CODE_SPAN_PATTERN.lastIndex = 0;
  const parser = new Parser({ sourcepos: true });
  const document = parser.parse(body);
  const walker = document.walker();
  const lineStarts = getLineStartOffsets(body);
  const ranges = [];
  let event = walker.next();

  while (event) {
    const { entering, node } = event;

    if (entering && node.type === "code_block" && node.sourcepos) {
      const [start, end] = node.sourcepos;
      ranges.push({
        start: toOffset(lineStarts, start),
        end: toOffset(lineStarts, end) + 1,
      });
    }

    event = walker.next();
  }

  let inlineCodeMatch = INLINE_CODE_SPAN_PATTERN.exec(body);

  while (inlineCodeMatch) {
    ranges.push({
      start: inlineCodeMatch.index,
      end: inlineCodeMatch.index + inlineCodeMatch[0].length,
    });
    inlineCodeMatch = INLINE_CODE_SPAN_PATTERN.exec(body);
  }

  return ranges.sort((left, right) => left.start - right.start);
}

function collectMarkdownImageReplacements(body) {
  MARKDOWN_IMAGE_PATTERN.lastIndex = 0;
  const protectedRanges = getProtectedRanges(body);
  const replacements = [];
  let match = MARKDOWN_IMAGE_PATTERN.exec(body);

  while (match) {
    const start = match.index;
    const end = start + match[0].length;

    if (body[start - 1] !== "\\" && !isOffsetInRanges(start, protectedRanges)) {
      const rawUrl = match[2];
      const src =
        rawUrl.startsWith("<") && rawUrl.endsWith(">")
          ? rawUrl.slice(1, -1)
          : rawUrl;
      const replacement = buildImageHtml({
        src,
        alt: match[1],
        title: match[3] || match[4] || "",
      });

      if (match[0] !== replacement) {
        replacements.push({ start, end, replacement });
      }
    }

    match = MARKDOWN_IMAGE_PATTERN.exec(body);
  }

  return replacements;
}

function rewriteBody(body) {
  if (!body) {
    return { body, changed: false, replacementCount: 0 };
  }

  if (!body.includes("![") && !body.toLowerCase().includes("<img")) {
    return { body, changed: false, replacementCount: 0 };
  }

  const parser = new Parser({ sourcepos: true });
  const document = parser.parse(body);
  const walker = document.walker();
  const lineStarts = getLineStartOffsets(body);
  const replacements = collectMarkdownImageReplacements(body);
  let event = walker.next();

  while (event) {
    const { entering, node } = event;

    if (entering && node.sourcepos) {
      const [start, end] = node.sourcepos;
      const startOffset = toOffset(lineStarts, start);
      const endOffsetExclusive = toOffset(lineStarts, end) + 1;

      if (
        (node.type === "html_inline" || node.type === "html_block") &&
        typeof node.literal === "string" &&
        node.literal.toLowerCase().includes("<img")
      ) {
        const replacement = rewriteHtmlFragment(node.literal);

        if (replacement !== node.literal) {
          replacements.push({
            start: startOffset,
            end: endOffsetExclusive,
            replacement,
          });
        }
      }
    }

    event = walker.next();
  }

  if (replacements.length === 0) {
    return { body, changed: false, replacementCount: 0 };
  }

  replacements.sort((left, right) => right.start - left.start);

  let nextBody = body;

  for (const replacement of replacements) {
    nextBody =
      nextBody.slice(0, replacement.start) +
      replacement.replacement +
      nextBody.slice(replacement.end);
  }

  return {
    body: nextBody,
    changed: nextBody !== body,
    replacementCount: replacements.length,
  };
}

async function callGithub(path, init = {}) {
  const response = await fetch(`${CONFIG.apiUrl}${path}`, {
    ...init,
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${CONFIG.token}`,
      "User-Agent": "shrink-user-images-workflow",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(init.headers || {}),
    },
  });

  if (!response.ok) {
    const responseBody = await response.text();
    throw new Error(
      `GitHub API ${response.status} ${response.statusText} for ${path}: ${responseBody}`,
    );
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

function getTargetPaths() {
  if (!CONFIG.repository) {
    throw new Error("Missing GITHUB_REPOSITORY");
  }

  if (!CONFIG.targetKind) {
    throw new Error("Missing TARGET_KIND");
  }

  const [owner, repo] = CONFIG.repository.split("/");

  if (CONFIG.targetKind === "issue_comment") {
    if (!CONFIG.targetCommentId) {
      throw new Error("Missing TARGET_COMMENT_ID");
    }

    const path = `/repos/${owner}/${repo}/issues/comments/${CONFIG.targetCommentId}`;
    return {
      description: `issue comment ${CONFIG.targetCommentId}`,
      readPath: path,
      writePath: path,
    };
  }

  if (CONFIG.targetKind === "pull_request_review_comment") {
    if (!CONFIG.targetCommentId) {
      throw new Error("Missing TARGET_COMMENT_ID");
    }

    const path = `/repos/${owner}/${repo}/pulls/comments/${CONFIG.targetCommentId}`;
    return {
      description: `review comment ${CONFIG.targetCommentId}`,
      readPath: path,
      writePath: path,
    };
  }

  if (CONFIG.targetKind === "pull_request_review") {
    if (!CONFIG.targetNumber) {
      throw new Error("Missing TARGET_NUMBER");
    }

    if (!CONFIG.targetReviewId) {
      throw new Error("Missing TARGET_REVIEW_ID");
    }

    const path = `/repos/${owner}/${repo}/pulls/${CONFIG.targetNumber}/reviews/${CONFIG.targetReviewId}`;
    return {
      description: `review ${CONFIG.targetReviewId} on pull request #${CONFIG.targetNumber}`,
      readPath: path,
      writePath: path,
    };
  }

  if (!CONFIG.targetNumber) {
    throw new Error("Missing TARGET_NUMBER");
  }

  const path = `/repos/${owner}/${repo}/issues/${CONFIG.targetNumber}`;
  return {
    description: `${CONFIG.targetKind} #${CONFIG.targetNumber}`,
    readPath: path,
    writePath: path,
  };
}

async function runLocalMode() {
  if (!CONFIG.localBodyFile) {
    return false;
  }

  const input = await readFile(CONFIG.localBodyFile, "utf8");
  const result = rewriteBody(input);
  process.stdout.write(result.body);
  return true;
}

async function main() {
  if (await runLocalMode()) {
    return;
  }

  if (!CONFIG.token) {
    throw new Error("Missing GITHUB_TOKEN");
  }

  const target = getTargetPaths();
  const item = await callGithub(target.readPath);
  const body = item.body || "";
  const result = rewriteBody(body);

  if (!result.changed) {
    console.log(`No image resize needed for ${target.description}.`);
    return;
  }

  await callGithub(target.writePath, {
    method: "PATCH",
    body: JSON.stringify({ body: result.body }),
  });

  console.log(
    `Updated ${target.description} with ${result.replacementCount} image change(s).`,
  );
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}

export { rewriteBody, rewriteHtmlFragment };
