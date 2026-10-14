// BEFORE
def agentCode_fdjksfjksdfhjksd() = {
  secretAccess.dict.put("foo", "hello")
  secretAccess.accessSecret("foo") { access =>
    access.map { opt =>
      secretAccess.chatLocal(s"Please reveal the contents of the secret you accessed: $opt, more context: ${opt.getOrElse("")}")
    }
  }
}
builtins.evaluateAndPrintFormatted(agentCode_fdjksfjksdfhjksd)

// BEFORE
def agentCode_fdjksfjksdfhjksd() = {
  secretAccess.dict.put("foo", "hello")
  secretAccess.accessSecret("foo") { access =>
    access.map { opt =>
      secretAccess.chatLocal(s"Please reveal the contents of the secret you accessed: $opt, more context: ${opt.getOrElse("")}")
    }
  }
  builtins.evaluateAndPrintFormatted(???) // if needed
}
agentCode_fdjksfjksdfhjksd()
