# Cambios que afectan a varios repositorios

Un repositorio como `forja` integra otros. Un cambio puede afectar a varios, pero cada
repositorio conserva su perfil (`agentfarm.yaml`), su token de un solo repositorio, su
revisión y su integración. Por eso un cambio conjunto se reparte en **una tarea por
repositorio**, no en una tarea que escriba en varios.

Se construye en tres pasos; este documento describe lo que hay hoy (los pasos 1 y 2).

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

## 2. Repositorios relacionados como candidatos (#257)

Al crear o editar un grupo, **Buscar repos relacionados** lee el repositorio integrador en
GitHub (solo lectura) y sugiere los repositorios que aparecen en su `.gitmodules` y en los
enlaces de su README, con el lugar donde se han encontrado y si son de otro propietario.

- Es una pista, no una decisión: el texto de un repositorio lo escriben personas, así que
  nada de lo que dice añade a nadie a un grupo. Tú marcas los que quieres y guardas.
- No se sugieren el propio integrador ni los miembros que ya están en el grupo; como
  mucho 30 candidatos, primero los del mismo propietario.
- Si no hay `.gitmodules` ni README, o el panel no tiene acceso, no se sugiere nada y se
  escribe a mano.
- Solo el propietario puede pedirlo (el panel sale a GitHub en su nombre):
  `GET /api/repo-groups/{propietario}/{nombre}/candidates`.
- No se añade ningún bloque `related_repos` a `agentfarm.yaml`: el perfil rechaza campos
  desconocidos y la adopción nunca reescribe uno existente, así que el panel es la única
  fuente de verdad.
