# Low-output functional transport controls

Initial hardware VP9 640x360/15/300 request emitted 24-44 kbps; functional controls only:

| Initial video metric | Classic | CoC | GATT |
| --- | --- | --- | --- |
| Launch -> video s | 3.86 | 3.23 | 3.88 |
| Received / rendered | 300 /299 | 305 /304 | 229 /228 |
| Rendered FPS | 15.1 | 15.1 | 15.1 |
| Sender queue / drops | 0/30 /0 | 0/30 /0 | 0/30 /0 |
| Write average / max ms | 0.6 /5.2 | 1.9 /27.0 | 38.1 /69.5 |
| Source / receiver CPU snapshot % | 14.2 /32.1 | 17.8 /29.6 | 25.0 /37.0 |
| Source / receiver PSS MB | 103 /119 | 107 /119 | 107 /119 |

CPU values are single snapshots; run order and scene were not controlled. The full suite reached source thermal status Moderate (40.2 C skin / 38.4 C); this is not a transport thermal ranking.
