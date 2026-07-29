# Manager release identity

- Alias: `manager-release`
- Certificate SHA-256 (DER): `d4547457cc1309ac95c89b0200675eca8db48f80f89f2f0c99a623b0176b2d1c`
- Validity: 20 years
- Provider pin input: `managerCertificateSha256=d4547457cc1309ac95c89b0200675eca8db48f80f89f2f0c99a623b0176b2d1c`

`manager-release.jks` and `manager-release-password.txt` are local release secrets and are ignored by Git. Back them up together; loss requires issuing a new Manager release identity and rebuilding the split Provider pin.
