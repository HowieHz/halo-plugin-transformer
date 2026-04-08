import fs from "node:fs/promises";
import path from "node:path";

const packageJsonPaths = ["package.json", path.join("ui", "package.json")];
const managedWorkflowPaths = [
  path.join(".github", "workflows", "ci.yaml"),
  path.join(".github", "workflows", "cd.yaml"),
  path.join(".github", "workflows", "update-toolchain-versions.yml"),
];
const dryRun = process.argv.includes("--dry-run");
const releaseDelayMs = 24 * 60 * 60 * 1000;
const userAgent = "halo-plugin-transformer-toolchain-updater";

const appendGitHubOutput = async (name, value) => {
  const outputPath = process.env.GITHUB_OUTPUT;
  if (!outputPath) {
    return;
  }
  await fs.appendFile(outputPath, `${name}=${value}\n`, "utf8");
};

const stripUtf8Bom = (content) => content.replace(/^\uFEFF/u, "");

const detectJsonIndent = (content) => {
  const match = content.match(/\n( +)"/u);
  return match ? match[1].length : 2;
};

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");

const parseTimestamp = (value) => {
  if (typeof value !== "string") {
    return null;
  }
  const normalizedValue = value.trim();
  if (normalizedValue === "") {
    return null;
  }
  const timestamp = Date.parse(normalizedValue);
  return Number.isNaN(timestamp) ? null : timestamp;
};

const parseSemVer = (version) => {
  const match = /^v?(\d+)\.(\d+)\.(\d+)$/u.exec(version);
  if (!match) {
    return null;
  }
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
  };
};

const hasReleaseDelayElapsed = (publishedAt, now = Date.now()) => {
  const publishedTimestamp = parseTimestamp(publishedAt);
  if (publishedTimestamp === null) {
    throw new Error(`Invalid published timestamp: ${publishedAt || "<empty>"}`);
  }
  return now - publishedTimestamp >= releaseDelayMs;
};

const logSkippedLatest = (ecosystem, version, publishedAt) => {
  console.log(
    [
      `Skipping ${ecosystem} update because the current latest release ${version} was published at ${publishedAt}.`,
      "The updater only adopts the current latest after a full 24-hour delay and never falls back to an older version while that latest is still cooling down.",
    ].join(" "),
  );
};

const fetchLatestNodeMajor = async () => {
  const response = await fetch("https://api.github.com/repos/nodejs/node/releases/latest", {
    headers: {
      Accept: "application/vnd.github+json",
      "User-Agent": userAgent,
    },
  });
  if (!response.ok) {
    throw new Error(
      `Failed to fetch the latest Node.js release: ${response.status} ${response.statusText}`,
    );
  }
  const latestRelease = await response.json();
  const latestVersion =
    typeof latestRelease.tag_name === "string" ? latestRelease.tag_name.trim() : "";
  const publishedAt =
    typeof latestRelease.published_at === "string" ? latestRelease.published_at.trim() : "";
  const parsedVersion = parseSemVer(latestVersion);
  if (!parsedVersion) {
    throw new Error(`Invalid latest Node.js release version: ${latestVersion || "<empty>"}`);
  }
  if (!hasReleaseDelayElapsed(publishedAt)) {
    logSkippedLatest("Node.js", latestVersion, publishedAt);
    return null;
  }
  return parsedVersion.major;
};

const fetchLatestPnpmVersion = async () => {
  const response = await fetch("https://registry.npmjs.org/pnpm", {
    headers: {
      Accept: "application/json",
      "User-Agent": userAgent,
    },
  });
  if (!response.ok) {
    throw new Error(`Failed to fetch pnpm metadata: ${response.status} ${response.statusText}`);
  }
  const metadata = await response.json();
  const latestVersion =
    typeof metadata["dist-tags"]?.latest === "string" ? metadata["dist-tags"].latest.trim() : "";
  const publishedAt =
    typeof metadata.time?.[latestVersion] === "string" ? metadata.time[latestVersion].trim() : "";
  if (!parseSemVer(latestVersion)) {
    throw new Error(`Invalid pnpm latest version: ${latestVersion || "<empty>"}`);
  }
  if (!hasReleaseDelayElapsed(publishedAt)) {
    logSkippedLatest("pnpm", latestVersion, publishedAt);
    return null;
  }
  return latestVersion;
};

const readFileWithLineEnding = async (filePath) => {
  const content = stripUtf8Bom(await fs.readFile(path.resolve(process.cwd(), filePath), "utf8"));
  return {
    content,
    lineEnding: content.includes("\r\n") ? "\r\n" : "\n",
  };
};

const maybeWriteFile = async (filePath, nextContent) => {
  if (dryRun) {
    return;
  }
  await fs.writeFile(path.resolve(process.cwd(), filePath), nextContent, "utf8");
};

const updatePackageJson = async (filePath, nodeMajor, pnpmVersion) => {
  const { content, lineEnding } = await readFileWithLineEnding(filePath);
  const packageJson = JSON.parse(content);
  const updatedFields = [];
  const indent = detectJsonIndent(content);

  packageJson.engines ??= {};

  if (nodeMajor !== null) {
    const nextNodeRange = `>=${nodeMajor}`;
    if (packageJson.engines.node !== nextNodeRange) {
      packageJson.engines.node = nextNodeRange;
      updatedFields.push(`engines.node -> ${nextNodeRange}`);
    }
  }

  if (pnpmVersion !== null) {
    const nextPnpmRange = `^${pnpmVersion}`;
    const nextPackageManager = `pnpm@${pnpmVersion}`;

    if (packageJson.engines.pnpm !== nextPnpmRange) {
      packageJson.engines.pnpm = nextPnpmRange;
      updatedFields.push(`engines.pnpm -> ${nextPnpmRange}`);
    }

    if (packageJson.packageManager !== nextPackageManager) {
      packageJson.packageManager = nextPackageManager;
      updatedFields.push(`packageManager -> ${nextPackageManager}`);
    }
  }

  if (updatedFields.length === 0) {
    return [];
  }

  const nextContent = `${JSON.stringify(packageJson, null, indent)}${lineEnding}`.replace(
    /\n/gu,
    lineEnding,
  );
  await maybeWriteFile(filePath, nextContent);
  return updatedFields;
};

const replaceWorkflowInputValue = (content, inputName, nextValue) => {
  const pattern = new RegExp(
    `^(\\s*${escapeRegExp(inputName)}:\\s*)(["']?)([^"'#\\s]+)\\2(\\s*(?:#.*)?)$`,
    "gmu",
  );
  let changed = false;
  const nextContent = content.replace(pattern, (full, prefix, quote, currentValue, suffix) => {
    if (currentValue === nextValue) {
      return full;
    }
    changed = true;
    return `${prefix}${quote}${nextValue}${quote}${suffix}`;
  });
  return {
    changed,
    nextContent,
  };
};

const updateWorkflowFile = async (filePath, nodeMajor, pnpmVersion) => {
  const { content, lineEnding } = await readFileWithLineEnding(filePath);
  const updates = [];
  let nextContent = content;

  if (nodeMajor !== null && nextContent.includes("node-version:")) {
    const result = replaceWorkflowInputValue(nextContent, "node-version", String(nodeMajor));
    if (result.changed) {
      nextContent = result.nextContent;
      updates.push(`node-version -> ${nodeMajor}`);
    }
  }

  if (pnpmVersion !== null && nextContent.includes("pnpm-version:")) {
    const result = replaceWorkflowInputValue(nextContent, "pnpm-version", pnpmVersion);
    if (result.changed) {
      nextContent = result.nextContent;
      updates.push(`pnpm-version -> ${pnpmVersion}`);
    }
  }

  if (pnpmVersion !== null && nextContent.includes("version:")) {
    const result = replaceWorkflowInputValue(nextContent, "version", pnpmVersion);
    if (result.changed) {
      nextContent = result.nextContent;
      updates.push(`pnpm action version -> ${pnpmVersion}`);
    }
  }

  if (updates.length === 0) {
    return [];
  }

  await maybeWriteFile(filePath, nextContent.replace(/\n/gu, lineEnding));
  return updates;
};

const updateGroups = [];
const nodeMajor = await fetchLatestNodeMajor();
const pnpmVersion = await fetchLatestPnpmVersion();

for (const filePath of packageJsonPaths) {
  const updates = await updatePackageJson(filePath, nodeMajor, pnpmVersion);
  if (updates.length > 0) {
    updateGroups.push({ filePath, updates });
  }
}

for (const filePath of managedWorkflowPaths) {
  const updates = await updateWorkflowFile(filePath, nodeMajor, pnpmVersion);
  if (updates.length > 0) {
    updateGroups.push({ filePath, updates });
  }
}

if (updateGroups.length === 0) {
  console.log("No toolchain version updates were required");
} else {
  for (const { filePath, updates } of updateGroups) {
    console.log(`Updated ${filePath}`);
    for (const update of updates) {
      console.log(`- ${update}`);
    }
  }
}

await appendGitHubOutput("changed", updateGroups.length > 0 ? "true" : "false");
await appendGitHubOutput("node_major", nodeMajor === null ? "" : String(nodeMajor));
await appendGitHubOutput("pnpm_version", pnpmVersion ?? "");
await appendGitHubOutput("updated_files", updateGroups.map(({ filePath }) => filePath).join(","));
