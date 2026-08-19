This directory contains the Java Opus codec implementation from the
Concentus project, vendored directly rather than pulled from a third-party
Maven republish (a published artifact under a different Maven coordinate
did not expose this package correctly when tried first -- see project
commit history).

Source: https://github.com/lostromb/concentus
Path:   Java/Concentus/src/main/java/org/concentus
License: see LICENSE.txt in this directory (permissive, BSD-style,
         redistribution-friendly -- same terms as the Opus reference
         library itself).

Not modified from upstream. If Concentus publishes a proper release,
consider switching back to a dependency instead of vendored source.
