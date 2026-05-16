# Repository Knowledge Map

`myra-transport` is the network transport layer for the MVP Express stack.

Source-verified modules:
- `lib` - transport API, NIO and io_uring backends, framing, lifecycle, memory, retry/error handling
- `myra-server` - server adapter over the transport layer
- `benchmarks` - performance benchmarks

Start here:
- `README.md` - public overview
- `docs/guide.md` - user guide
- `docs/white-paper.md` - design narrative
- `docs/internals/` - implementation notes
- `docs/quality/README.md` - validation harness

Important package roots:
- `express.mvp.myra.transport`
- `express.mvp.myra.transport.framing`
- `express.mvp.myra.transport.lifecycle`
- `express.mvp.myra.transport.memory`
- `express.mvp.myra.transport.nio`
- `express.mvp.myra.transport.iouring`
- `express.mvp.myra.server`

Agent rule:
- verify Java/platform claims against Gradle and source before repeating them; current build config uses Java 25 Javadoc settings
