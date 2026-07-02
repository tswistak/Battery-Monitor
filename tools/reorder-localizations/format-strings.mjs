import { access, readFile, readdir, writeFile } from "fs/promises";
import path from "path";
import { fileURLToPath } from "url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, "../..");
const resDir = path.join(repoRoot, "app", "res");
const topLevelIndent = "  ";

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});

async function main() {
  const filePaths = await findStringFiles();
  const flattenedByPath = new Map();

  for (const filePath of filePaths) {
    const original = normalizeNewlines(await readFile(filePath, "utf8"));
    const reorderedAttributes = normalizeAttributeOrderDocument(original);
    flattenedByPath.set(
      filePath,
      flattenDocument(reorderedAttributes, filePath),
    );
  }

  const englishPath = path.join(resDir, "values", "strings.xml");
  const flattenedEnglish = flattenedByPath.get(englishPath);

  if (!flattenedEnglish) {
    throw new Error("Missing app/res/values/strings.xml");
  }

  const blankLineAfterLineNumbers = collectBlankLineAfterLineNumbers(
    parseResourcesDocument(flattenedEnglish, englishPath),
  );

  let changedFiles = 0;

  for (const filePath of filePaths) {
    const original = normalizeNewlines(await readFile(filePath, "utf8"));
    const flattened = flattenedByPath.get(filePath);

    if (!flattened) {
      throw new Error(`Missing flattened content for ${filePath}`);
    }

    const withBlankLines = applyBlankLines(
      flattened,
      filePath,
      blankLineAfterLineNumbers,
    );
    const formatted = expandEscapedNewlinesInDocument(withBlankLines, filePath);

    if (formatted === original) {
      continue;
    }

    await writeFile(filePath, formatted, "utf8");
    changedFiles += 1;
    console.log(`formatted ${path.relative(repoRoot, filePath)}`);
  }

  if (changedFiles === 0) {
    console.log("all strings.xml files already match the target layout");
    return;
  }

  console.log(`files formatted: ${changedFiles}`);
}

async function findStringFiles() {
  const directories = await readdir(resDir, { withFileTypes: true });
  const filePaths = [];

  for (const directory of directories) {
    if (!directory.isDirectory()) {
      continue;
    }

    if (directory.name !== "values" && !directory.name.startsWith("values-")) {
      continue;
    }

    const filePath = path.join(resDir, directory.name, "strings.xml");

    try {
      await access(filePath);
      filePaths.push(filePath);
    } catch {
      continue;
    }
  }

  return filePaths.sort((left, right) => left.localeCompare(right));
}

function flattenDocument(content, filePath) {
  const document = parseResourcesDocument(content, filePath);
  let output = document.prefix;
  let cursor = 0;

  for (const block of document.blocks) {
    output += document.inner.slice(cursor, block.start);
    output += flattenBlock(block).raw;
    cursor = block.end;
  }

  output += document.inner.slice(cursor);
  output += document.suffix;
  return output;
}

function normalizeAttributeOrderDocument(content) {
  return content.replace(
    /(<string\b[^>]*?)\bformatted=(['"][^'"]*['"])\s+\bname=(['"][^'"]*['"])([^>]*>)/g,
    "$1name=$3 formatted=$2$4",
  );
}

function applyBlankLines(content, filePath, blankLineAfterLineNumbers) {
  const document = parseResourcesDocument(content, filePath);
  return rebuildDocument(document, document.blocks, blankLineAfterLineNumbers);
}

function rebuildDocument(document, blocks, blankLineAfterLineNumbers) {
  if (blocks.length === 0) {
    return document.prefix + document.suffix;
  }

  let output = document.prefix + "\n";
  let resourceLineIndex = 0;

  for (let index = 0; index < blocks.length; index += 1) {
    const block = blocks[index];
    output += topLevelIndent + block.raw;

    if (index === blocks.length - 1) {
      output += "\n";
      continue;
    }

    const addBlankLine =
      block.tagName && blankLineAfterLineNumbers.has(resourceLineIndex);
    output += addBlankLine ? "\n\n" : "\n";

    if (block.tagName) {
      resourceLineIndex += 1;
    }
  }

  return output + document.suffix;
}

function flattenBlock(block) {
  if (block.tagName !== "string") {
    return block;
  }

  const openEnd = findTagEnd(block.raw, 0);
  const closeStart = block.raw.lastIndexOf("</string>");

  if (closeStart === -1) {
    throw new Error(`Missing closing </string> for ${block.name}`);
  }

  const openTag = block.raw.slice(0, openEnd);
  const inner = block.raw.slice(openEnd, closeStart);
  const closeTag = block.raw.slice(closeStart);
  const flattenedInner = inner.includes("\n")
    ? flattenMultilineStringValue(inner)
    : inner;

  return {
    ...block,
    raw: `${openTag}${flattenedInner}${closeTag}`,
  };
}

function expandEscapedNewlinesInDocument(content, filePath) {
  const document = parseResourcesDocument(content, filePath);
  let output = document.prefix;
  let cursor = 0;

  for (const block of document.blocks) {
    output += document.inner.slice(cursor, block.start);
    output += expandEscapedNewlinesInBlock(block).raw;
    cursor = block.end;
  }

  output += document.inner.slice(cursor);
  output += document.suffix;
  return output;
}

function expandEscapedNewlinesInBlock(block) {
  if (block.tagName !== "string") {
    return block;
  }

  return {
    ...block,
    raw: block.raw.replaceAll("\\n", "\\n\n    "),
  };
}

function flattenMultilineStringValue(value) {
  const normalized = normalizeNewlines(value);
  let output = "";
  let pendingWhitespace = false;
  let lastTokenWasEscapedNewline = false;

  for (let index = 0; index < normalized.length; index += 1) {
    if (normalized.startsWith("\\n", index)) {
      output += "\\n";
      pendingWhitespace = false;
      lastTokenWasEscapedNewline = true;
      index += 1;
      continue;
    }

    const character = normalized[index];

    if (/\s/.test(character)) {
      pendingWhitespace = true;
      continue;
    }

    if (pendingWhitespace && output.length > 0 && !lastTokenWasEscapedNewline) {
      output += " ";
    }

    output += character;
    pendingWhitespace = false;
    lastTokenWasEscapedNewline = false;
  }

  return output;
}

function collectBlankLineAfterLineNumbers(document) {
  const lineNumbers = new Set();
  let resourceLineIndex = 0;

  for (let index = 0; index < document.blocks.length; index += 1) {
    const block = document.blocks[index];

    if (!block.tagName) {
      continue;
    }

    let nextResourceBlock = null;

    for (
      let nextIndex = index + 1;
      nextIndex < document.blocks.length;
      nextIndex += 1
    ) {
      if (document.blocks[nextIndex].tagName) {
        nextResourceBlock = document.blocks[nextIndex];
        break;
      }
    }

    if (!nextResourceBlock) {
      continue;
    }

    const between = document.inner.slice(block.end, nextResourceBlock.start);

    if (between.includes("\n\n")) {
      lineNumbers.add(resourceLineIndex);
    }

    resourceLineIndex += 1;
  }

  return lineNumbers;
}

function parseResourcesDocument(content, filePath) {
  const resourcesOpenStart = content.indexOf("<resources");

  if (resourcesOpenStart === -1) {
    throw new Error(`Missing <resources> in ${filePath}`);
  }

  const resourcesOpenEnd = findTagEnd(content, resourcesOpenStart);
  const resourcesCloseStart = content.lastIndexOf("</resources>");

  if (resourcesCloseStart === -1 || resourcesCloseStart < resourcesOpenEnd) {
    throw new Error(`Missing </resources> in ${filePath}`);
  }

  const prefix = content.slice(0, resourcesOpenEnd);
  const inner = content.slice(resourcesOpenEnd, resourcesCloseStart);
  const suffix = content.slice(resourcesCloseStart);
  const blocks = [];

  let cursor = 0;

  while (cursor < inner.length) {
    const nextOpen = inner.indexOf("<", cursor);

    if (nextOpen === -1) {
      break;
    }

    cursor = nextOpen;

    if (inner.startsWith("<!--", cursor)) {
      const commentEnd = inner.indexOf("-->", cursor + 4);

      if (commentEnd === -1) {
        throw new Error(`Unclosed comment in ${filePath}`);
      }

      blocks.push({
        start: cursor,
        end: commentEnd + 3,
        name: null,
        raw: inner.slice(cursor, commentEnd + 3),
        tagName: null,
      });
      cursor = commentEnd + 3;
      continue;
    }

    const openEnd = findTagEnd(inner, cursor);
    const openTag = inner.slice(cursor, openEnd);
    const tagName = getTagName(openTag);

    if (!tagName) {
      throw new Error(`Could not parse tag name in ${filePath}`);
    }

    const end = isSelfClosingTag(openTag)
      ? openEnd
      : findClosingTagEnd(inner, tagName, openEnd);

    blocks.push({
      start: cursor,
      end,
      name: getNameAttribute(openTag),
      raw: inner.slice(cursor, end),
      tagName,
    });
    cursor = end;
  }

  return {
    blocks,
    inner,
    prefix,
    suffix,
  };
}

function findTagEnd(content, start) {
  let index = start;
  let quote = null;

  while (index < content.length) {
    const character = content[index];

    if (quote) {
      if (character === quote) {
        quote = null;
      }

      index += 1;
      continue;
    }

    if (character === '"' || character === "'") {
      quote = character;
      index += 1;
      continue;
    }

    if (character === ">") {
      return index + 1;
    }

    index += 1;
  }

  throw new Error("Unclosed tag");
}

function findClosingTagEnd(content, tagName, searchStart) {
  const closeTag = `</${tagName}>`;
  const closeStart = content.indexOf(closeTag, searchStart);

  if (closeStart === -1) {
    throw new Error(`Missing closing tag for <${tagName}>`);
  }

  return closeStart + closeTag.length;
}

function getTagName(openTag) {
  const match = openTag.match(/^<([A-Za-z0-9_.:-]+)/);
  return match?.[1] ?? null;
}

function getNameAttribute(openTag) {
  const match = openTag.match(/\bname\s*=\s*(['"])(.*?)\1/s);
  return match?.[2] ?? null;
}

function isSelfClosingTag(openTag) {
  return /\/>\s*$/.test(openTag);
}

function normalizeNewlines(value) {
  return value.replace(/\r\n?/g, "\n");
}
