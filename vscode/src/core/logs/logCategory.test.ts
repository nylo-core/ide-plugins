import * as assert from 'assert';
import { isError, matchesCategory } from './logCategory';
import { LogEntry, NetworkKind, StandardLine, NetworkEntry, RawLine } from './logModel';

function std(level: string | null, message: string): StandardLine {
  return {
    entryType: 'standard',
    timestamp: null,
    timestampRaw: '-',
    sessionTag: 'tag',
    level,
    message,
    context: null,
    stack: null,
    raw: message,
    lineStart: 1,
    lineEnd: 1,
  };
}

function net(netKind: NetworkKind): NetworkEntry {
  return {
    entryType: 'network',
    netKind,
    requestId: 'id',
    method: 'GET',
    uri: 'http://x',
    statusCode: null,
    statusMessage: null,
    responseTimeMs: null,
    payloadSize: null,
    summary: 's',
    raw: 's',
    lineStart: 1,
    lineEnd: 1,
  };
}

const rawLine: RawLine = { entryType: 'raw', raw: '#0 frame', lineStart: 1, lineEnd: 1 };

function assertMembership(entry: LogEntry, console: boolean, networking: boolean, errors: boolean): void {
  assert.strictEqual(matchesCategory(entry, 'console'), console);
  assert.strictEqual(matchesCategory(entry, 'networking'), networking);
  assert.strictEqual(matchesCategory(entry, 'errors'), errors);
}

describe('logCategory', () => {
  it('console and plain log entries are CONSOLE only', () => {
    assertMembership(std('debug', 'ok'), true, false, false);
    assertMembership(std('info', 'fyi'), true, false, false);
    assertMembership(std(null, 'raw print'), true, false, false);
    assertMembership(rawLine, true, false, false);
  });

  it('error-level lines are both CONSOLE and ERRORS', () => {
    assertMembership(std('error', 'boom'), true, false, true);
    assertMembership(std('warning', 'careful'), true, false, true);
  });

  it('network entries are NETWORKING and only error-kind is also ERRORS', () => {
    assertMembership(net('request'), false, true, false);
    assertMembership(net('response'), false, true, false);
    assertMembership(net('error'), false, true, true);
  });

  it('ALL matches everything', () => {
    [std('debug', 'x'), std('error', 'x'), net('request'), net('error'), rawLine].forEach((e) => {
      assert.ok(matchesCategory(e, 'all'));
    });
  });

  it('isError covers error and warning lines and network errors only', () => {
    assert.ok(isError(std('error', 'boom')));
    assert.ok(isError(std('warning', 'careful')));
    assert.ok(isError(net('error')));
    assert.ok(!isError(std('debug', 'ok')));
    assert.ok(!isError(net('request')));
    assert.ok(!isError(rawLine));
  });
});
