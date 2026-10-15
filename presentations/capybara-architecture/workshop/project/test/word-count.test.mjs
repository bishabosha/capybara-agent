import test from 'node:test';
import assert from 'node:assert/strict';
import { countWords } from '../src/word-count.mjs';

test('empty and whitespace-only input has no words', () => {
  for (const value of ['', '   ', '\t\n']) assert.equal(countWords(value), 0);
});

test('ordinary space-separated input', () => {
  assert.equal(countWords('capybara agent'), 2);
  assert.equal(countWords('capybara'), 1);
});

test('mixed whitespace separates words without empty words', () => {
  assert.equal(countWords('  capybara   agent\tkeeps\nworking  '), 4);
});
