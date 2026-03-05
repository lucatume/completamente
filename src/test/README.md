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
- More than 15 lines: `myFixture.configureByFile()` (file in `src/test/testData/completion`)

## Generating test data

### `edit-types-harness-output.jsonp`

Required by `EditKindFromHarnessTest.kt`. Generate it from the project root:

```bash
source .venv/bin/activate && python3 bin/harness-test-edit-types.py
```

The output file is written to `src/test/testData/completion/edit-types-harness-output.jsonp`.
