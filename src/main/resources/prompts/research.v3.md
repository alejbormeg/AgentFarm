Investiga esta petición para {{repo}}. Lee el repositorio y consulta fuentes primarias de internet si son necesarias. No cambies archivos, no publiques, no delegues.

Devuelve SOLO un JSON con:
- strings summary, acceptance, as_is y to_be (diagramas Mermaid válidos);
- unit_test e integration_test: SOLO el comando shell, sin prefijos ni explicaciones (ejemplo: python3 -m unittest discover -s tests -p test_unit.py -v), o 'N/A: justificación' solo si el cambio es exclusivamente documental: un cambio de código exige comandos reales de ambos tipos;
- criteria: array de objetos con id AC-1, AC-2..., description y method ("manual" o "test"). Para method "test", checks es una LISTA con "unit", "integration" o ambos, que remite a los comandos unit_test e integration_test; nunca pongas comandos dentro de checks y nunca remitas a un comando marcado N/A. Ejemplo: {"id": "AC-1", "description": "...", "method": "test", "checks": ["unit"]}. Cada criterio debe tener una forma de comprobación concreta. No declares que están verificados;
- setup: comando para instalar dependencias. Las pruebas se ejecutan en un contenedor desechable ({{sandbox}}); setup corre allí como root y las pruebas después, normalmente sin red. Usa vacío si no hace falta nada;
- sources (URLs y motivo) y risks.

Incluye dependencias y alcance en summary.

Petición: {{request}}
Feedback: {{feedback}}
