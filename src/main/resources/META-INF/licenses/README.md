# Third-party licences

`dependencies.txt` lists the exact Java dependencies bundled in this JAR.
Their original licence, notice and attribution files are preserved under
`dependencies/`, separated by dependency and version. Host-provided libraries
are not included. Upstream dependency lists are retained as original documents,
not as inventories of this distribution.

`supplements/` contains upstream legal texts missing from those JARs:

| Dependency | Upstream source |
| --- | --- |
| opcZip 1.2.0 | [49d753b](https://github.com/rzymek/opczip/tree/49d753b50fbf6ee339e0e48d451bc4074f67e218) |
| Java JSON Canonicalization 1.1 | [v1.1](https://github.com/erdtman/java-json-canonicalization/tree/571f10dcc0c9531164795c38b934a58cf7172722) |
| FastExcel 0.20.0 | [0.20.0](https://github.com/dhatim/fastexcel/tree/b93e596e00ffa6d329f0f127699544c6a4fcf949) |
| Reactive Streams 1.0.4 | [v1.0.4](https://github.com/reactive-streams/reactive-streams-jvm/tree/944163a4b2477a2bebaaada86b0ba910b6302f2f) |
| AWS EventStream 1.0.1 | [7be2dd8](https://github.com/awslabs/aws-eventstream-java/tree/7be2dd80e12f8835674c8ffb0f4a2efb64c7b585) |
| Netty 4.1.118.Final | [netty-4.1.118.Final](https://github.com/netty/netty/tree/36f95cfaeed0c1313b21f1b5350c19436ae7fb45) |

Netty's original notice also names optional components. Retaining that notice
does not mean they are bundled here. The accompanying texts cover its embedded
derivations, including shaded JCTools. Review these supplements when updating
dependencies; package verification checks their presence, not legal clearance.

`FastDoubleParser-MIT.txt` supplies the MIT text referenced by the parser's
notice in AWS `third-party-jackson-core` 2.31.1. Its upstream JAR contains an
Apache licence in that file's place. The MIT text comes from the exact
[revision linked by the notice](https://github.com/wrandelshofer/FastDoubleParser/blob/522be16e145f43308c43b23094e31d5efcaa580e/LICENSE).

HistoForge's licence is in `licenses/LICENSE`. The bundled Python wheel retains
its own licence and vendored-code attribution.
