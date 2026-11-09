# Código de terceros servido por el panel

| Paquete | Versión | Licencia | Origen | Archivos | SHA-256 del conjunto |
|---|---|---|---|---|---|
| Mermaid | 11.12.0 | MIT (`mermaid/LICENSE`) | build ESM minificado; la versión es la que declara el propio bundle. Entró en 2dd26e7 sin registrar su origen | 75 | `17abd3567f2f88b531aeddeffe184ed717fa081452b69e9bcf987e96d551766f` |

El SHA-256 del conjunto es el de `find mermaid -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum`.
`tests/vendor_inventory.sh` lo recalcula y falla si no coincide: actualizar Mermaid exige actualizar
esta tabla en el mismo cambio.

No se ha auditado el código minificado. Mermaid se carga solo para dibujar diagramas, con
`securityLevel: "strict"`, y la política de contenido no deja que ejecute scripts de otro origen.
