# Steam Workshop source notice

`core/steam-protocol` and `core/workshop-download` include adapted source files
from **WorkshopAndroidDownloader**,
<https://github.com/Apricityx/WorkshopAndroidDownloader>, commit
`6443f81d5a462da52d3d3a5cbb22a265df39e6db`.

Upstream is licensed under the **Apache License, Version 2.0** (an earlier
revision of this notice incorrectly said GPLv3). A verbatim copy of the license
is kept beside this file as [`LICENSE.Apache-2.0`](LICENSE.Apache-2.0); the
canonical text is also at <http://www.apache.org/licenses/LICENSE-2.0>. Upstream
ships no `NOTICE` file, so Apache-2.0 section 4(d) adds no attribution text to
propagate.

The adapted files keep their upstream copyright and attribution notices. This
project distributes the Manager as a whole under GPLv3, a direction of
combination Apache-2.0 permits; that does not change the original license of
these files, which remains Apache-2.0.

**Modifications made by this project** (Apache-2.0 section 4(b)): the Kotlin
package names are left unchanged (`top.apricityx.workshop.*`), while the
adaptations disable HTTP endpoints, disable automatic redirects, and disable
anonymous CDN fallback. Downloaded content remains in Manager-private staging
until the user explicitly imports a validated Mod. The Manager keeps these
components isolated from the game loader.

**Scope, so these modules are not mistaken for dead code:** this upstream
supplies the Steam CM protocol and authentication transport, CDN and depot
manifest handling, the download engine, chunk processing, and integrity
verification. It has *not* been replaced by Workshop-Native, which covers only
public Workshop browse-page parsing, artwork retry behaviour, and Steam Guard
interaction semantics — see the Workshop-Native section of
[`THIRD_PARTY_NOTICES.md`](../../../../THIRD_PARTY_NOTICES.md).
