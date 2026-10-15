---scala
(id = "capstone-call", seconds = 75, layout = "standard")
---

## An accepted source transformation

```scala
coding.transformSource { source =>
  source.replace("return text.split(' ').length;",
    """const clean = text.trim();
  return clean === '' ? 0 : clean.split(/\s+/u).length;""")
}
puts(coding.test())
puts(coding.diff())
```

The callback uses only its string argument. Test execution and output use the supplied capabilities outside that callback.

The complete deterministic snippet also shows the baseline failure and sandbox checks.


{{stack footer}}

Source: [runnable snippet](assets/source/coding-snippet.html), [end-to-end integration test](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

The source-only transformation fixes empty input and mixed whitespace. The baseline has one passing and two failing cases; the final suite has three passes. The tool reports fixed overall status, not untrusted child-supplied counts. The JSON escaping regression ensures a diff containing /\s+/ remains valid structured output.
