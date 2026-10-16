puts(filesystem.list("."))
puts(filesystem.read("Greeting.scala"))
editing.edit("Greeting.scala") { text =>
  text.replace("hello, world", "hello, capybara")
}
filesystem.write("notes.txt", "Edited through the editing capability.\n")
puts(filesystem.read("Greeting.scala"))
puts(filesystem.read("notes.txt"))
