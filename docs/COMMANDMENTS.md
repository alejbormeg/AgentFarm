# Los Siete Mandamientos

Son las reglas que cumple cada candidato antes de poder integrarse. En la novela de
Orwell alguien repinta el muro del granero de noche. Aquí están en el código del panel
y un agente que intente cambiar las reglas del proyecto incumple el séptimo.

Se ven en **Gobierno** y, para cada tarea, al abrirla. Un mandamiento incumplido bloquea
la integración.

| # | Mandamiento | Quién lo comprueba y cómo |
| --- | --- | --- |
| 1 | Todo cambio de código pasa pruebas unitarias y de integración en un entorno limpio. | El [verificador](EVIDENCE.md#verificador) del panel lee la CI del commit. `N/A:` solo vale para documentación. |
| 2 | Todo bug corregido trae una prueba que antes fallaba. | Con la receta `bugfix`, el ejecutor lleva las pruebas nuevas al commit base y exige que fallen. Admite excepción firmada. |
| 3 | Ningún agente revisa su propio trabajo. | Revisión en un turno aparte, de solo lectura. Avisa si usa el mismo motor; para cruzarlos, `AGENTFARM_PROVIDER_REVIEW`. |
| 4 | Ningún agente toca más de lo aprobado. | El ejecutor antes de publicar y el panel con los archivos reales, contra `allowed_paths`. |
| 5 | Ninguna métrica de calidad empeora sin una excepción firmada. | El ejecutor mide las métricas de `agentfarm.yaml` en la base y en el candidato. |
| 6 | Ningún secreto ni vulnerabilidad conocida llega a la rama principal. | Formatos conocidos de credenciales: el ejecutor antes de hacer push y el panel sobre el diff. |
| 7 | Ningún agente cambia estos mandamientos. | Cambiar `agentfarm.yaml` o la CI del proyecto lo incumple. Solo adoptar un repo puede añadir `agentfarm.yaml`. |

## Estados

- **Cumplido:** hay una comprobación concreta que lo confirma.
- **Incumplido:** bloquea. Los mandamientos 2 y 5 admiten una excepción firmada por el
  propietario, que queda en el expediente; los demás, no.
- **Pendiente:** falta leer la CI de este commit.
- **Sin evaluar:** no hay datos para decidirlo y no bloquea. Sin `agentfarm.yaml` no hay
  rutas aprobadas ni métricas, y queda a la vista.
- **No aplica:** por ejemplo, el mandamiento 2 en una tarea que no corrige un bug.

## Declarar las puertas de calidad

```yaml
quality:
  checks:                    # deben terminar con código 0
    lint: ruff check .
    audit: pip-audit         # sin esto, las vulnerabilidades quedan sin comprobar
  metrics:                   # imprimen un número al final; no puede empeorar
    coverage: pytest --cov=src | awk '/^TOTAL/ {print $NF}'
    warnings: {command: ruff check . | wc -l, better: lower}
```

Cada comando corre en el mismo contenedor desechable que las pruebas, normalmente sin red:
una auditoría de dependencias necesita `container.network: always`. Máximo ocho puertas.
La primera vez que una métrica da un número, se acepta como referencia.

## Qué no garantizan

- Un test que pasa no es un buen test: una cobertura que no baja ayuda, pero no lo demuestra.
- La búsqueda de secretos reconoce formatos conocidos, no cualquier contraseña.
- La revisión es de un modelo: encuentra problemas, pero no certifica que no los haya.
- Los mandamientos 2, 3 y 5 se basan en lo que informa el ejecutor. El panel comprueba por
  su cuenta el 1, el 4, el 6 y el 7.
- Con las pruebas en el host (`environment: local`) no se evalúan el 2 ni las métricas.
