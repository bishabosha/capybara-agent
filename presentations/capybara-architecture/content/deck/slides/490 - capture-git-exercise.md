---scala
(id = "capture-git-exercise", seconds = 360, layout = "dark-slide")
---

## Exercise 3: a pure callback cannot retain coding or output

Try each independently in universe `coding`:

```scala
coding.transformSource { source =>
  puts(source)
  source
}
```

```scala
coding.transformSource(source => coding.read("README.md"))
```

**Pass condition:** both fail compilation before any source edit. Explain which capability each callback retains, then move reporting outside the transformation.


{{stack footer}}

Source: [pure callback signature](assets/source/coding-interface.html), [safe-harness rejection tests](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

The first callback retains output through puts. The second retains coding through read. String -> String promises an empty capture set. Merely having a supplied capability in the enclosing snippet does not permit retaining it in this callback. These rejections are executed in WorkshopSuite through ReplExec, not predicted from syntax alone.
