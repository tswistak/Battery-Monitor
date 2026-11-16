import { load } from "cheerio";
import { readFile, readdir } from "node:fs/promises";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const RESOURCE_NAME = "latest_release_changelog";
const UTF8 = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });

async function readText(path) {
  return UTF8.decode(await readFile(path));
}

function decodeAndroidString(text) {
  text = text.replace(/^[ \t\n\r\f\v]+|[ \t\n\r\f\v]+$/g, "");
  if (/^[@?]/.test(text)) {
    throw new Error("The changelog must contain literal text, not a resource reference");
  }
  let result = "";
  let quoted = false;
  let previousSpace = false;
  for (let index = 0; index < text.length; index += 1) {
    let char = text[index];
    if (!quoted && /[ \t\n\r\f\v]/.test(char)) {
      if (!previousSpace) result += " ";
      previousSpace = true;
      continue;
    }
    previousSpace = false;
    if (char === "\\") {
      index += 1;
      if (index === text.length) {
        throw new Error("The changelog ends in an incomplete Android escape");
      }
      char = text[index];
      if (char === "u") {
        const digits = text.slice(index + 1, index + 5);
        if (!/^[0-9a-fA-F]{4}$/.test(digits)) {
          throw new Error("The changelog contains an invalid Android Unicode escape");
        }
        char = String.fromCharCode(Number.parseInt(digits, 16));
        index += 4;
      } else {
        char = { n: "\n", t: "\t" }[char] ?? char;
      }
      result += char;
    } else if (char === '"') {
      quoted = !quoted;
    } else if (char === "'" && !quoted) {
      throw new Error("The changelog contains an unescaped Android apostrophe");
    } else {
      result += char;
    }
  }
  if (quoted) throw new Error("The changelog contains an unclosed Android quoted string");
  if (!result.isWellFormed()) {
    throw new Error("The changelog contains an unpaired Unicode surrogate escape");
  }
  return result;
}

async function readResources(path) {
  // XML normalizes literal line endings before decoding entities such as &#13;.
  const xml = (await readText(path)).replace(/\r\n?/g, "\n");
  const $ = load(xml, { xmlMode: true, decodeEntities: true });
  const roots = $.root().children();
  if (roots.length !== 1 || roots[0].name !== "resources") {
    throw new Error(`${relative(ROOT, path)} must have a resources root element`);
  }
  return { $, resources: roots };
}

function withoutFinalLineEnding(text) {
  if (text.endsWith("\r\n")) return text.slice(0, -2);
  if (text.endsWith("\n")) return text.slice(0, -1);
  return text;
}

async function verifyReleaseChangelog() {
  const properties = await readText(join(ROOT, "fdroid-version.properties"));
  const codes = [...properties.matchAll(/^[ \t]*versionCode[ \t]*=[ \t]*(.*?)[ \t]*\r?$/gm)];
  if (codes.length !== 1 || !/^[0-9]+$/.test(codes[0][1])) {
    throw new Error("fdroid-version.properties must contain exactly one integer versionCode");
  }
  const versionCode = codes[0][1];
  if (Number(versionCode) <= 0 || Number(versionCode) > 2147483647) {
    throw new Error("versionCode must be a positive Android version code");
  }
  const fdroidFile = join(ROOT, `fastlane/metadata/android/en-US/changelogs/${versionCode}.txt`);
  const resourceFile = join(ROOT, "app/src/main/res/values/changelog.xml");
  const { $, resources } = await readResources(resourceFile);
  const matches = resources.children().filter((_, node) => $(node).attr("name") === RESOURCE_NAME);
  if (matches.length !== 1 || matches[0].name !== "string") {
    throw new Error(`changelog.xml must contain exactly one ${RESOURCE_NAME} string resource`);
  }
  const source = join(ROOT, "app/src");
  for (const variant of await readdir(source, { withFileTypes: true })) {
    if (!variant.isDirectory()) continue;
    const res = join(source, variant.name, "res");
    let directories;
    try {
      directories = await readdir(res, { withFileTypes: true });
    } catch (error) {
      if (error.code === "ENOENT") continue;
      throw error;
    }
    for (const directory of directories) {
      if (!directory.isDirectory() || !directory.name.startsWith("values")) continue;
      const values = join(res, directory.name);
      for (const file of await readdir(values)) {
        const path = join(values, file);
        if (!file.endsWith(".xml") || path === resourceFile) continue;
        const other = await readResources(path);
        if (other.resources.children().toArray().some((node) => other.$(node).attr("name") === RESOURCE_NAME)) {
          throw new Error(`Duplicate ${RESOURCE_NAME} resource in ${relative(ROOT, path)}; localized and build-variant overrides are not allowed`);
        }
      }
    }
  }
  const resource = matches.first();
  for (const attribute of ["translatable", "formatted"]) {
    if (resource.attr(attribute) !== "false") {
      throw new Error(`${RESOURCE_NAME} must have ${attribute}="false"`);
    }
  }
  if (resource.children().length) {
    throw new Error(`${RESOURCE_NAME} must be plain text without nested XML elements`);
  }
  const appText = decodeAndroidString(resource.text());
  const fdroidText = await readText(fdroidFile);
  if (!appText.trim() || !fdroidText.trim()) {
    throw new Error("Both the in-app and F-Droid changelogs must be nonempty");
  }
  if (withoutFinalLineEnding(appText) !== withoutFinalLineEnding(fdroidText)) {
    throw new Error(
      "In-app release notes differ from F-Droid notes. Only one final LF/CRLF is ignored.\n" +
      `${relative(ROOT, fdroidFile)}: ${JSON.stringify(fdroidText)}\n` +
      `${relative(ROOT, resourceFile)} (displayed text): ${JSON.stringify(appText)}`,
    );
  }
  console.log(`In-app and F-Droid release notes match for versionCode ${versionCode}`);
}

verifyReleaseChangelog().catch((error) => {
  console.error(`Release changelog verification failed: ${error.message}`);
  process.exitCode = 1;
});
