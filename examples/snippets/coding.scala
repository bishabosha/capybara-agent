puts(coding.sandboxChecks())
puts(coding.test())
coding.transformSource { source =>
  source.replace("return text.split(' ').length;",
    """const clean = text.trim();
  return clean === '' ? 0 : clean.split(/\s+/u).length;""")
}
puts(coding.test())
puts(coding.diff())
