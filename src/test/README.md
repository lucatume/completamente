# Testing

## Conventions

All tests:
* MUST extend `com.github.lucatume.completamente.BaseCompletionTestCase`.
* MUST NOT use mocks or partial mocking: use real instances and primitives.
* MUST NOT fake the filesystem: use real test files.
* MUST cover all code paths, including exceptions.

Add complex fixture helpers to `BaseCompletionTestCase`.

## Test file creation

- 15 or fewer lines: `myFixture.configureByText()`
- More than 15 lines: `myFixture.configureByFile()` (file in `src/test/testData/`)

