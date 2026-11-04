# Comprobaciones de calidad

`./mvnw verify` falla si una de estas se incumple. No necesitan red ni cuota, salvo descargar los plugins la primera vez.

| Comprobación | Qué vigila | Si falla |
| --- | --- | --- |
| Spotless | Un solo formato en `src`, `runtime` y `tests` | `./mvnw spotless:apply` |
| [`ApiContractTest`](../src/test/java/io/agentfarm/ApiContractTest.java) | Claves y tipos de cada respuesta de la API, sobre una granja con una tarea en cada etapa. Nunca guarda valores | Si es a propósito, regenera (abajo) y lee el diff: la interfaz y el ejecutor dependen de esas claves |
| [`ArchitectureTest`](../src/test/java/io/agentfarm/ArchitectureTest.java) | La dirección de la arquitectura ([#144](https://github.com/alejbormeg/AgentFarm/issues/144)) | Corrige la dependencia. No añadas la violación al almacén |
| SpotBugs | Errores comunes, en `spotbugs-exclude.xml` cada exclusión dice por qué | Corrige el fallo o justifica la exclusión |
| `-Xlint` | Avisos del compilador. Hoy no hay ninguno en nuestro código | Corrígelo |

Spotless y SpotBugs solo corren con Java 21, porque su resultado no depende del JDK, y no corren al construir la imagen.

## Escaneo de seguridad

`tests/security_scan.sh` lo corren `validar-local.sh` y la CI. No necesita claves ni envía el código fuera: Trivy descarga su base de vulnerabilidades y mira el jar, y gitleaks lee el historial en local.

- **Trivy** falla con una vulnerabilidad alta o crítica que ya tenga arreglo. Actualiza la dependencia.
- **gitleaks** falla con un posible secreto en el historial. Si es real, revócalo: borrarlo del repositorio no basta. Si es falso, añade su huella a `.gitleaksignore`.

## Regenerar lo generado

```bash
# Tras cambiar la API a propósito:
./mvnw test -Dtest=ApiContractTest -Dcontract.update=true

# Solo con una regla nueva o cambiada de ArchitectureTest:
./mvnw test -Dtest=ArchitectureTest -Darchunit.freeze.store.default.allowStoreCreation=true
```

Lo que ya incumple una regla congelada está en `src/test/resources/archunit_store`. **Ese almacén solo debe encoger**: ArchUnit quita sola cada violación que desaparece, y hay que incluir ese cambio en el commit. Una violación nueva rompe el build, y la manera de arreglarla es corregirla, no regenerar el almacén.

## Cobertura

JaCoCo deja el informe en `target/site/jacoco/index.html`. No se exige un mínimo, porque un número obligatorio invita a escribir pruebas sin valor. Sirve para comparar antes y después de un refactor.

Punto de partida, 4 de noviembre de 2026, solo pruebas de módulo:

| Paquete | Líneas | Ramas |
| --- | --- | --- |
| `core` | 89,2 % de 4.380 | 76,5 % de 2.833 |
| `web` | 77,4 % de 468 | 58,9 % de 190 |
| `ui` | 27,8 % de 395 | 15,0 % de 140 |
| Raíz (`Cli`, `McpServer`, arranque) | 50,9 % de 291 | 49,2 % de 122 |
| **Total** | **81,8 % de 5.534** | **71,8 % de 3.285** |

`runtime/FarmDaemon.java` no aparece porque no pasa por Maven: solo lo ejercita `tests/e2e/run.sh` ([#87](https://github.com/alejbormeg/AgentFarm/issues/87)).
