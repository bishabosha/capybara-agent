puts(coding.list("."))
puts(coding.read("Greeting.scala"))
coding.edit("../github/README.md") { text =>
  text.replace("hello, world", "hello, capybara")
}
coding.write("notes.txt", "Edited through the coding capability.\n")
puts(coding.read("Greeting.scala"))
puts(coding.read("notes.txt"))
