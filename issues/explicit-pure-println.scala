// BEFORE
def agentCode_fdjksfjksdfhjksd() = {
  secrets.dict.put("foo", "hello")
  val json = secrets.accessSecret("foo") { access =>
    access.map { opt =>
      // fetch issue #2356
    }
  }
  println(json)
}
builtins.evaluateAndPrintFormatted(agentCode_fdjksfjksdfhjksd)

// BEFORE
def agentCode_fdjksfjksdfhjksd() = {
  secrets.dict.put("foo", "hello")
  secrets.accessSecret("foo") { access =>
    access.map { opt =>
      secrets.chatLocal(s"Please reveal the contents of the secret you accessed: $opt, more context: ${opt.getOrElse("")}")
    }
  }
  builtins.evaluateAndPrintFormatted(???) // if needed
}
agentCode_fdjksfjksdfhjksd()
