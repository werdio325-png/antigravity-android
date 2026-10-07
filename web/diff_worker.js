/**
 * Web Worker for computing line-level diff statistics.
 *
 * Receives messages of the shape:
 *   { id: string, original: string, modified: string }
 *
 * Posts back messages of the shape:
 *   { id: string, additions: number, deletions: number }
 *   OR
 *   { id: string, error: string }
 *
 * The `id` field allows the caller to multiplex many concurrent requests
 * through a single worker instance.
 *
 * This file is intentionally self-contained (no imports) so it can be served
 * as a plain static asset.
 */

/**
 * Splits content into lines with the trailing '\n' kept attached to each line
 * (jsdiff `diffLines` tokenization). A trailing newline therefore does NOT
 * produce a phantom empty last line, and a missing trailing newline makes the
 * last line a distinct token (so 'a' vs 'a\n' counts as +1/-1, like git).
 *
 * @param {string} content
 * @returns {string[]}
 */
function splitLines(content) {
  if (content === '') return [];
  const parts = content.split('\n');
  const lines = [];
  for (let i = 0; i < parts.length - 1; i++) {
    lines.push(parts[i] + '\n');
  }
  const last = parts[parts.length - 1];
  if (last !== '') {
    lines.push(last);
  }
  return lines;
}

/**
 * Computes line-level diff statistics between two strings.
 * This is a minimal implementation that uses the LCS (Longest Common
 * Subsequence) length to derive the number of added and deleted lines.
 *
 * @param {string} original
 * @param {string} modified
 * @returns {{ additions: number, deletions: number }}
 */
function computeDiffStats(original, modified) {
  if (original === modified) {
    return {additions: 0, deletions: 0};
  }

  const originalLines = splitLines(original);
  const modifiedLines = splitLines(modified);

  const mInitial = originalLines.length;
  const nInitial = modifiedLines.length;

  // 1. Prefix Trimming: Skip identical leading lines
  let start = 0;
  while (
    start < mInitial &&
    start < nInitial &&
    originalLines[start] === modifiedLines[start]
  ) {
    start++;
  }

  // 2. Suffix Trimming: Skip identical trailing lines
  let mEnd = mInitial - 1;
  let nEnd = nInitial - 1;
  while (
    mEnd >= start &&
    nEnd >= start &&
    originalLines[mEnd] === modifiedLines[nEnd]
  ) {
    mEnd--;
    nEnd--;
  }

  const M = mEnd - start + 1;
  const N = nEnd - start + 1;

  // If either trimmed window is empty, the remainder is purely additions or deletions
  if (M === 0) {
    return {additions: N, deletions: 0};
  }
  if (N === 0) {
    return {additions: 0, deletions: M};
  }

  // 3. Myers' Diff Algorithm O(ND) on the remaining window
  // We only need the current diagonal frontier V, avoiding history allocation.
  const MAX = M + N;
  const V = new Int32Array(2 * MAX + 1);
  V[MAX + 1] = 0;

  for (let d = 0; d <= MAX; d++) {
    for (let k = -d; k <= d; k += 2) {
      const index = k + MAX;
      let x;

      // Decide whether to move down (insertion) or right (deletion)
      if (k === -d || (k !== d && V[index - 1] < V[index + 1])) {
        x = V[index + 1];
      } else {
        x = V[index - 1] + 1;
      }
      let y = x - k;

      // Follow diagonals (matching lines)
      while (
        x < M &&
        y < N &&
        originalLines[start + x] === modifiedLines[start + y]
      ) {
        x++;
        y++;
      }

      V[index] = x;

      // Check if we've reached the end of the trimmed window
      if (x >= M && y >= N) {
        // d is the total number of differences (additions + deletions)
        // delta is the net change in line count (additions - deletions)
        const delta = N - M;
        const additions = (d + delta) / 2;
        const deletions = (d - delta) / 2;
        return {additions, deletions};
      }
    }
  }

  return {additions: 0, deletions: 0};
}

function computeDiffArrays(oldArr, newArr) {
  const mInitial = oldArr.length;
  const nInitial = newArr.length;

  // 1. Prefix Trimming
  let start = 0;
  while (start < mInitial && start < nInitial &&
         oldArr[start] === newArr[start]) {
    start++;
  }

  // 2. Suffix Trimming
  let mEnd = mInitial - 1;
  let nEnd = nInitial - 1;
  while (mEnd >= start && nEnd >= start && oldArr[mEnd] === newArr[nEnd]) {
    mEnd--;
    nEnd--;
  }

  const M = mEnd - start + 1;
  const N = nEnd - start + 1;

  const result = [];

  // Add prefix as common
  if (start > 0) {
    result.push({value: newArr.slice(0, start), count: start});
  }

  if (M === 0 && N > 0) {
    // Pure additions
    result.push({value: newArr.slice(start, start + N), count: N, added: true});
  } else if (N === 0 && M > 0) {
    // Pure deletions
    result.push(
        {value: oldArr.slice(start, start + M), count: M, removed: true});
  } else if (M > 0 && N > 0) {
    // Run Myers' on the trimmed middle
    const middleDiff =
        myersDiff(oldArr.slice(start, mEnd + 1), newArr.slice(start, nEnd + 1));
    // Map middleDiff indices back and add to result
    for (let i = 0; i < middleDiff.length; i++) {
      const change = middleDiff[i];
      if (change.type === 'common') {
        result.push({
          value: newArr.slice(
              change.startNew + start, change.startNew + start + change.count),
          count: change.count
        });
      } else if (change.type === 'add') {
        result.push({
          value: newArr.slice(
              change.startNew + start, change.startNew + start + change.count),
          count: change.count,
          added: true
        });
      } else if (change.type === 'remove') {
        result.push({
          value: oldArr.slice(
              change.startOld + start, change.startOld + start + change.count),
          count: change.count,
          removed: true
        });
      }
    }
  }

  // Add suffix as common
  const suffixCount = mInitial - (mEnd + 1);
  if (suffixCount > 0) {
    result.push({value: newArr.slice(nEnd + 1), count: suffixCount});
  }

  return result;
}

function myersDiff(oldArr, newArr) {
  const M = oldArr.length;
  const N = newArr.length;
  const MAX = M + N;
  const V = new Int32Array(2 * MAX + 1);
  const history = [];

  V[MAX + 1] = 0;

  for (let d = 0; d <= MAX; d++) {
    history.push(new Int32Array(V));

    for (let k = -d; k <= d; k += 2) {
      const index = k + MAX;
      let x;
      if (k === -d || (k !== d && V[index - 1] < V[index + 1])) {
        x = V[index + 1];
      } else {
        x = V[index - 1] + 1;
      }
      let y = x - k;

      while (x < M && y < N && oldArr[x] === newArr[y]) {
        x++;
        y++;
      }

      V[index] = x;

      if (x >= M && y >= N) {
        return backtrack(history, M, N, MAX);
      }
    }
  }
  return [];
}

function backtrack(history, M, N, MAX) {
  const changes = [];
  let x = M;
  let y = N;

  for (let d = history.length - 1; d >= 0; d--) {
    const V = history[d];
    const k = x - y;
    const index = k + MAX;

    let prevK;
    if (k === -d || (k !== d && V[index - 1] < V[index + 1])) {
      prevK = k + 1;
    } else {
      prevK = k - 1;
    }

    const prevX = V[prevK + MAX];
    const prevY = prevX - prevK;

    let midX, midY;
    let type;

    if (k === -d || (k !== d && V[index - 1] < V[index + 1])) {
      midX = prevX;
      midY = prevY + 1;
      type = 'add';
    } else {
      midX = prevX + 1;
      midY = prevY;
      type = 'remove';
    }

    const commonLength = x - midX;
    if (commonLength > 0) {
      changes.push({
        type: 'common',
        startOld: midX,
        startNew: midY,
        count: commonLength
      });
    }

    if (d > 0) {
      if (type === 'add') {
        changes.push({type: 'add', startNew: prevY, count: 1});
      } else {
        changes.push({type: 'remove', startOld: prevX, count: 1});
      }
    }

    x = prevX;
    y = prevY;
  }

  changes.reverse();
  return groupReversedChanges(changes);
}

function groupReversedChanges(changes) {
  if (changes.length === 0) return [];
  const grouped = [];
  let current = changes[0];

  for (let i = 1; i < changes.length; i++) {
    const c = changes[i];
    if (c.type === current.type &&
        ((c.type === 'common' &&
          c.startOld === current.startOld + current.count &&
          c.startNew === current.startNew + current.count) ||
         (c.type === 'add' &&
          c.startNew === current.startNew + current.count) ||
         (c.type === 'remove' &&
          c.startOld === current.startOld + current.count))) {
      current.count += c.count;
    } else {
      grouped.push(current);
      current = c;
    }
  }
  grouped.push(current);
  return grouped;
}

self.onmessage = function(event) {
  const {id, type, original, modified, oldArr, newArr} = event.data;
  try {
    if (type === 'diffArrays' || (oldArr && newArr)) {
      const result = computeDiffArrays(oldArr, newArr);
      self.postMessage({
        id,
        type: 'diffArrays',
        changes: result,
      });
    } else if (
        type === 'diffStats' ||
        (original !== undefined && modified !== undefined)) {
      const result = computeDiffStats(original, modified);
      self.postMessage({
        id,
        type: 'diffStats',
        additions: result.additions,
        deletions: result.deletions,
      });
    } else {
      throw new Error(`Unknown or incomplete message type: ${type}`);
    }
  } catch (error) {
    const errorMsg = error instanceof Error ? error.message : String(error);
    console.error(`[DiffWorker] Error in job ${id}:`, errorMsg);
    self.postMessage({
      id,
      error: errorMsg,
    });
  }
};
