# Cambios que afectan a varios repositorios

Un repositorio como `forja` integra otros. Un cambio puede afectar a varios, pero cada
repositorio conserva su perfil (`agentfarm.yaml`), su token de un solo repositorio, su
revisión y su integración. Por eso un cambio conjunto se reparte en **una tarea por
repositorio**, no en una tarea que escriba en varios.

Se construye en tres pasos; este documento describe lo que hay hoy.

## 1. Grupos de repositorios (#256)

Gobierno → **Repositorios relacionados**. Un grupo tiene un repositorio **integrador** y
una lista de **miembros** con el papel de cada uno («implementa el contrato», «despliega»).

- Solo el propietario lo declara, en el panel. Un `agentfarm.yaml`, una propuesta o una
  issue no pueden ampliar un grupo: son texto de personas o de agentes.
- Un grupo no da permisos. Cada miembro sigue con su propio perfil, su propio token y tu
  aprobación; para trabajar en él hay que **adoptarlo**. El panel avisa de los miembros que
  aún no conoce.
- Validación: `propietario/nombre`, sin repetidos, el integrador no es miembro de su
  propio grupo, hasta 20 miembros y papeles de hasta 160 caracteres. Guardar sustituye la
  lista entera; nada se guarda a medias. Todo queda en la auditoría.

API (lectura abierta a las personas; escritura solo del propietario, con CSRF):

- `GET /api/repo-groups`
- `POST /api/repo-groups/{propietario}/{nombre}` con `{"members": [{"repo": "o/r", "role": "…"}]}`
- `POST /api/repo-groups/{propietario}/{nombre}/delete`
