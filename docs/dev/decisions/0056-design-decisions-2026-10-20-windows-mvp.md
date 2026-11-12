# Design decisions 2026-10-20 (Windows MVP)

- **Find PCs on by default for the Windows MVP**, after the phone->PC call and two-way scan tests pass on the new Android build (user: "On, after tests"). Until then it stays Experimental, off.
- **Windows distribution like TrenchHQ:** Microsoft Store (MSIX, Microsoft signs after acceptance, no code-signing certificate to buy) as the install channel; GitHub for source and release notes. MSIX packaging becomes MVP work, including re-proving the Bluetooth GATT behaviour (QW gates 1-2) under the packaged identity. Publisher account: a separate MeshLive account (Q-STORE-PUBLISHER, owner 2026-11-06).
