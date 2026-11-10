import path from 'node:path';
import { XMLParser, XMLValidator } from 'fast-xml-parser';
import sanitizeHtml from 'sanitize-html';
import { marked } from 'marked';
import { parse as parseYaml } from 'yaml';
import { ARTICLE_MAPPINGS } from './mappings.mjs';
import { REPOSITORY_URL } from './github.mjs';

const allowedTags = [
  'p',
  'br',
  'strong',
  'b',
  'em',
  'i',
  'u',
  'code',
  'pre',
  'ul',
  'ol',
  'li',
  'a',
  'h2',
  'h3',
  'h4',
  'blockquote',
  'sup',
  'sub',
];
const escapeText = (text) =>
  String(text).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
export function normalizeUrl(value, { commit, sourcePath = 'README.md' } = {}) {
  // eslint-disable-next-line no-control-regex -- Reject control characters in imported URLs.
  if (typeof value !== 'string' || /[\x00-\x20\x7f]/.test(value) || value.startsWith('//'))
    return null;
  try {
    let url;
    if (/^[a-z][a-z0-9+.-]*:/i.test(value)) url = new URL(value);
    else {
      if (!/^[a-f0-9]{40}$/i.test(commit ?? '')) return null;
      if (value.startsWith('#'))
        url = new URL(`${REPOSITORY_URL}/blob/${commit}/${sourcePath}${value}`);
      else {
        const relative = path.posix.normalize(
          value.startsWith('/')
            ? value.slice(1)
            : path.posix.join(path.posix.dirname(sourcePath), value),
        );
        if (relative.startsWith('../') || relative === '..') return null;
        url = new URL(`${REPOSITORY_URL}/blob/${commit}/${relative}`);
      }
    }
    if (!['https:', 'mailto:'].includes(url.protocol) || url.username || url.password) return null;
    if (
      url.protocol === 'mailto:' &&
      (url.search || url.hash || !/^[^?\s@]+@[^?\s@]+$/.test(url.pathname))
    )
      return null;
    return url.href;
  } catch {
    return null;
  }
}

export function sanitizeImportedHtml(html, context) {
  return sanitizeHtml(html, {
    allowedTags,
    allowedAttributes: { a: ['href', 'title'] },
    allowedSchemes: ['https', 'mailto'],
    allowProtocolRelative: false,
    transformTags: {
      a: (_tag, attrs) => ({
        tagName: 'a',
        attribs: normalizeUrl(attrs.href, context)
          ? { href: normalizeUrl(attrs.href, context) }
          : {},
      }),
    },
    nonTextTags: ['script', 'style', 'textarea', 'option', 'iframe'],
  });
}

export function normalizeAndroid(value) {
  let text = String(value)
    .replace(/\\u([a-f0-9]{4})/gi, (_, code) => String.fromCharCode(parseInt(code, 16)))
    .replace(/\\n/g, '\n')
    .replace(/\\t/g, ' ')
    .replace(/\\'/g, "'")
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, '\\')
    .replace(/\\([@?])/g, '$1');
  if (text.startsWith('"') && text.endsWith('"')) text = text.slice(1, -1);
  if (/%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[sdfoxegbch]/i.test(text.replaceAll('%%', '')))
    throw new Error('Unresolved Android formatting placeholder');
  return text.replaceAll('%%', '%').trim();
}

export function parseAndroidStrings(xml, context) {
  if (
    typeof xml !== 'string' ||
    xml.length > 5 * 1024 * 1024 ||
    /<!\s*(?:DOCTYPE|ENTITY)\b/i.test(xml)
  )
    throw new Error('Android XML must not contain a DTD or entity declaration');
  const valid = XMLValidator.validate(xml);
  if (valid !== true) throw new Error(`Invalid Android XML: ${valid.err.msg}`);
  const tree = new XMLParser({
    preserveOrder: true,
    ignoreAttributes: false,
    trimValues: false,
    parseTagValue: false,
    processEntities: true,
    htmlEntities: true,
  }).parse(xml);
  const resources = tree.find((node) => node.resources)?.resources;
  if (!resources) throw new Error('Android XML has no resources element');
  const strings = new Map();
  function serialize(nodes) {
    return nodes
      .map((node) => {
        if ('#text' in node) return escapeText(node['#text']);
        const tag = Object.keys(node).find((key) => key !== ':@');
        if (!tag || !Array.isArray(node[tag])) return '';
        const contents = serialize(node[tag]);
        if (!allowedTags.includes(tag)) return contents;
        const href = tag === 'a' ? normalizeUrl(node[':@']?.['@_href'], context) : null;
        return `<${tag}${href ? ` href="${escapeText(href).replaceAll('"', '&quot;')}"` : ''}>${contents}${tag === 'br' ? '' : `</${tag}>`}`;
      })
      .join('');
  }
  for (const node of resources) {
    if (!node.string) continue;
    const name = node[':@']?.['@_name'];
    if (typeof name !== 'string' || strings.has(name))
      throw new Error('Android XML contains an invalid or duplicate string name');
    // Keep unresolved formatted resources available for capability detection, but never render them.
    strings.set(name, serialize(node.string));
  }
  return strings;
}

function paragraphs(value, context) {
  const normalized = normalizeAndroid(value);
  return sanitizeImportedHtml(
    normalized
      .split(/\n\s*\n/)
      .map((part) => `<p>${part.replace(/\s*\n\s*/g, ' ').trim()}</p>`)
      .join(''),
    context,
  );
}

export function markdownHtml(markdown, context) {
  return sanitizeImportedHtml(marked.parse(markdown, { async: false }), context);
}

export function markdownSection(markdown, heading) {
  const lines = markdown.split(/\r?\n/);
  const start = lines.findIndex(
    (line) =>
      /^#{1,6}\s/.test(line) &&
      line
        .replace(/^#{1,6}\s+/, '')
        .trim()
        .toLowerCase() === heading.toLowerCase(),
  );
  if (start < 0) return null;
  const depth = lines[start].match(/^#+/)[0].length;
  let end = start + 1;
  while (end < lines.length && !new RegExp(`^#{1,${depth}}\\s`).test(lines[end])) end++;
  return lines
    .slice(start + 1, end)
    .join('\n')
    .trim();
}

const legacyKeys = {
  status_bar_icon_availability_help: ['status_bar_icon_settings_help'],
  status_bar_chip_availability_help: ['status_bar_chip_settings_help'],
  diag_help_capacity: ['advanced_help_capacity'],
  diag_help_counters: ['advanced_help_counters'],
  diag_help_troubleshooting: ['advanced_help_troubleshooting'],
  backup_restore_help_overview: ['general_backup_help'],
  pref_export_settings_help: ['settings_backup_help'],
  pref_export_alarms_help: ['alarms_backup_help'],
  pref_export_device_data_help: ['device_specific_backup_help'],
};

export function buildArticles({ strings, readme, capabilities, commit, omitted }) {
  const context = { commit, sourcePath: 'app/src/main/res/values/strings.xml' };
  const label = (key) =>
    strings.has(key)
      ? sanitizeHtml(normalizeAndroid(strings.get(key)), { allowedTags: [], allowedAttributes: {} })
      : null;
  const location = (keys) => {
    const names = keys?.map(label);
    return names?.length && names.every(Boolean) ? `<p>In the app: ${names.join(' → ')}.</p>` : '';
  };
  return ARTICLE_MAPPINGS.map((mapping) => {
    const sources = [];
    const sections = [];
    for (const section of mapping.sections) {
      if (section.capability && !capabilities[section.capability]) {
        omitted.push(`${mapping.id}/${section.title}: unsupported feature`);
        continue;
      }
      const fragments = [];
      for (const entry of section.keys) {
        const { helpKey: key, ...details } = typeof entry === 'string' ? { helpKey: entry } : entry;
        const actual = [key, ...(legacyKeys[key] ?? [])].find((candidate) =>
          strings.has(candidate),
        );
        if (!actual || sources.includes(actual)) continue;
        try {
          const titleKeys =
            details.titleKeys ??
            [details.titleKey ?? (key.endsWith('_help') ? key.slice(0, -5) : null)].filter(Boolean);
          const title =
            actual !== key && details.legacyTitle
              ? escapeText(details.legacyTitle)
              : details.title
                ? escapeText(details.title)
                : titleKeys.map(label).find(Boolean);
          const heading =
            title && title.toLowerCase() !== section.title.toLowerCase() ? `<h3>${title}</h3>` : '';
          fragments.push(
            `${heading}${location(details.navigation)}${paragraphs(strings.get(actual), context)}`,
          );
          sources.push(actual);
        } catch {
          omitted.push(
            `${mapping.id}/${actual}: unresolved formatting is not suitable for the web`,
          );
        }
      }
      if (!fragments.length && section.readmeHeading) {
        const source = markdownSection(readme, section.readmeHeading);
        if (source) {
          fragments.push(markdownHtml(source, { commit, sourcePath: 'README.md' }));
          sources.push(`README.md#${section.readmeHeading}`);
        }
      }
      if (fragments.length) {
        sections.push(
          `<h2>${escapeText(section.title)}</h2>${location(section.navigation)}${fragments.join('')}`,
        );
      } else
        omitted.push(
          `${mapping.id}/${section.title}: source section not available in this application revision`,
        );
    }
    const fallback = `<p>This application revision does not include a shared explanation for this topic. See the <a href="${REPOSITORY_URL}/tree/${commit}">corresponding application source</a> or <a href="/help/#contact">contact the project</a> for version-specific help.</p>`;
    const title =
      mapping.id === 'notifications' && !capabilities.liveUpdates
        ? 'Notifications'
        : mapping.id === 'charging-targets' && !capabilities.targets
          ? 'Time estimates'
          : mapping.title;
    const intro =
      mapping.id === 'charging-targets' && !capabilities.targets
        ? 'Understand how this app version estimates remaining time and why estimates vary.'
        : mapping.intro;
    return {
      id: mapping.id,
      title,
      html: `<p>${escapeText(intro)}</p>${sections.length ? sections.join('') : fallback}`,
      sources,
    };
  });
}

export function capabilitiesFrom(strings, readme) {
  return {
    alarms: strings.has('alarms_overview_help') || /### Battery Alarms/.test(readme),
    history: strings.has('pref_enable_logging') || /### History and Logs/.test(readme),
    widgets:
      strings.has('circle_widget') ||
      strings.has('widget_status_name') ||
      /### Widgets/.test(readme),
    liveUpdates: strings.has('status_bar_chip_settings') || strings.has('pref_live_update_display'),
    backups: strings.has('general_backup_help') || strings.has('backup_restore_help_overview'),
    privileged: strings.has('pref_use_privileged_access_help'),
    historyCharts: strings.has('history_charts'),
    modernWidgets: strings.has('history_charts') && /Status, Measurements and Trend/.test(readme),
    targets: strings.has('time_estimates_settings_help'),
  };
}

export function extractVersion(properties) {
  const version = properties.match(/^versionName\s*=\s*(.+)$/m)?.[1].trim();
  if (!version || !/^\d+(?:\.\d+){1,3}(?:[-+][a-z0-9.-]+)?$/i.test(version))
    throw new Error('fdroid-version.properties has no valid versionName');
  return version;
}

export function extractRequirements(gradle) {
  const apiMin = Number(gradle.match(/\bminSdk(?:Version)?\s*(?:=\s*)?(\d+)/)?.[1]);
  const android = {
    21: 5,
    22: 5.1,
    23: 6,
    24: 7,
    25: 7.1,
    26: 8,
    27: 8.1,
    28: 9,
    29: 10,
    30: 11,
    31: 12,
    32: 12.1,
    33: 13,
    34: 14,
    35: 15,
    36: 16,
    37: 17,
  };
  if (!android[apiMin])
    throw new Error('Application minimum Android API is missing or unsupported by the importer');
  return { apiMin, androidMin: android[apiMin] };
}

export function fundingFrom(yaml) {
  const empty = { github: null, liberapay: null, suppi: null };
  if (!yaml) return empty;
  if (yaml.length > 100000) throw new Error('Funding configuration is too large');
  const data = parseYaml(yaml, { maxAliasCount: 0 });
  if (!data || typeof data !== 'object' || Array.isArray(data))
    throw new Error('Invalid funding configuration');
  const first = (value) => (Array.isArray(value) ? value[0] : value);
  const handle = (value) =>
    typeof first(value) === 'string' && /^[a-z0-9_-]{1,50}$/i.test(first(value))
      ? first(value)
      : null;
  const github = handle(data.github),
    liberapay = handle(data.liberapay);
  const custom = Array.isArray(data.custom) ? data.custom : [data.custom];
  const suppi =
    custom.find(
      (value) => typeof value === 'string' && /^https:\/\/suppi\.pl\/[a-z0-9_-]+\/?$/i.test(value),
    ) ?? null;
  return {
    github: github ? `https://github.com/sponsors/${github}` : null,
    liberapay: liberapay ? `https://liberapay.com/${liberapay}/donate` : null,
    suppi,
  };
}

export function changelogFrom(releases, markdown, commit, { local = false } = {}) {
  if (local)
    return marked
      .lexer(markdown)
      .filter(
        (token) =>
          token.type === 'heading' &&
          token.depth === 2 &&
          /^(?:unreleased|v?\d+(?:\.\d+){1,3}(?:[-+][a-z0-9.-]+)?)$/i.test(token.text),
      )
      .map((heading) => ({
        version: heading.text.replace(/^v(?=\d)/i, ''),
        date: null,
        url: null,
        html: markdownHtml(markdownSection(markdown, heading.text) ?? '', {
          commit,
          sourcePath: 'CHANGELOG.md',
        }),
      }));
  return releases.flatMap((release) => {
    const version = release.tag_name.replace(/^v/, '');
    const section =
      markdownSection(markdown, version) ?? markdownSection(markdown, release.tag_name);
    return section === null
      ? []
      : [
          {
            version,
            date: release.published_at,
            url: `${REPOSITORY_URL}/releases/tag/${encodeURIComponent(release.tag_name)}`,
            html: markdownHtml(section, { commit, sourcePath: 'CHANGELOG.md' }),
          },
        ];
  });
}

export function buildContent({ files, provenance, release, releases = [] }) {
  const text = (path) => files.get(path)?.toString('utf8') ?? '';
  for (const path of [
    'README.md',
    'CHANGELOG.md',
    'fdroid-version.properties',
    'app/src/main/res/values/strings.xml',
    'LICENSE',
  ])
    if (!text(path)) throw new Error(`Required application source is missing: ${path}`);
  const gradle = text('app/build.gradle') || text('app/build.gradle.kts');
  if (!gradle) throw new Error('Required application Gradle metadata is missing');
  const context = {
    commit: provenance.appCommit,
    sourcePath: 'app/src/main/res/values/strings.xml',
  };
  const strings = parseAndroidStrings(text('app/src/main/res/values/strings.xml'), context);
  const version = extractVersion(text('fdroid-version.properties'));
  if (release && version !== release.tag_name.replace(/^v/, ''))
    throw new Error('Application versionName does not match its published release tag');
  const capabilities = capabilitiesFrom(strings, text('README.md'));
  const omitted = [...(provenance.omitted ?? [])];
  const resource = (key, fallback) => {
    const value = strings.get(key);
    if (value) return sanitizeImportedHtml(paragraphs(value, context), context);
    if (fallback)
      return markdownHtml(fallback, { commit: provenance.appCommit, sourcePath: 'README.md' });
    throw new Error(`Required explanatory resource is missing: ${key}`);
  };
  const privacyHtml = resource('help_privacy', markdownSection(text('README.md'), 'Privacy'));
  const attributionHtml = resource(
    'help_acknowledgments',
    markdownSection(text('README.md'), 'Project Background'),
  );
  const contactSource = strings.get('help_contact') ?? text('LICENSE');
  const contactEmail = contactSource.match(
    /(?:mailto:)?([a-z0-9._+-]+@[a-z0-9.-]+\.[a-z]{2,})/i,
  )?.[1];
  if (!contactEmail) throw new Error('Application source has no verified contact email');
  const result = {
    schemaVersion: 3,
    locale: 'en',
    provenance: { ...provenance, version, omitted },
    release: {
      version,
      publishedAt: release?.published_at ?? null,
      url: release?.html_url ?? `${REPOSITORY_URL}/releases/`,
      apkUrl: release?.apkUrl ?? null,
      signatureUrl: release?.signatureUrl ?? null,
      apkSha256: release?.apkSha256 ?? null,
    },
    capabilities,
    requirements: extractRequirements(gradle),
    app: {
      name: normalizeAndroid(strings.get('app_launcher_name') ?? ''),
      privacyHtml,
      attributionHtml,
      contactEmail,
      sourceUrl: `${REPOSITORY_URL}/tree/${provenance.appCommit}`,
      licenseUrl: `${REPOSITORY_URL}/blob/${provenance.appCommit}/LICENSE`,
    },
    articles: buildArticles({
      strings,
      readme: text('README.md'),
      capabilities,
      commit: provenance.appCommit,
      omitted,
    }),
    changelog: changelogFrom(releases, text('CHANGELOG.md'), provenance.appCommit, {
      local: provenance.mode === 'local',
    }),
    media: {},
    videos: {},
    funding: fundingFrom(''),
    omitted,
  };
  if (!result.app.name) throw new Error('Required application name is missing');
  if (provenance.mode !== 'local' && !result.changelog.some((entry) => entry.version === version))
    throw new Error(`CHANGELOG.md has no entry for the selected application release: ${version}`);
  return result;
}
