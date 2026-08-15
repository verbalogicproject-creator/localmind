# 070-xml-resources-parse

**Incident:** a double hyphen inside an XML comment, which the XML spec forbids.
`mergeDebugResources` and `parseDebugLocalResources` both failed with a bare
`ParseError at [row,col]` and no explanation.

Easy to introduce by carrying an em-dash habit over from shell or Kotlin, where the
same characters are harmless, and the file looks entirely correct.

The check uses a real XML parser. The first attempt used a regex and false-positived
on the `-->` terminator itself.
