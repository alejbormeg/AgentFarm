# Cambios que afectan a varios repositorios

Un repositorio como `forja` integra otros. Un cambio puede afectar a varios, pero cada
repositorio conserva su perfil (`agentfarm.yaml`), su token de un solo repositorio, su
revisión y su integración. Por eso un cambio conjunto se reparte en **una tarea por
repositorio**, no en una tarea que escriba en varios.

Se construye en tres pasos; este documento describe los tres.

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

## 3. Tarea padre con partes (#258)

Cuando el repositorio integrador (por ejemplo `forja`) tiene un grupo, Napoleon recibe en su
investigación la lista de los repositorios que integra, con su papel. Si el cambio pedido
afecta también a alguno, su propuesta trae `parts`: una parte por repositorio, con título,
lo que debe hacer y de qué partes anteriores depende. Napoleon **no lee ni modifica** esos
repositorios desde esta tarea: no se amplía ningún permiso del token (sigue siendo de un
solo repositorio). Cada parte se investiga de verdad en su repositorio.

**Lo que el panel exige** (el texto de Napoleon nunca decide):

- Una parte solo puede estar en el repositorio integrador o en un miembro del grupo que
  declaraste. Hasta 8 partes; una parte solo depende de las anteriores.
- Si el plan no cumple, se conserva (no se pierde el turno de investigación), no se reparte
  y el panel muestra por qué: se aprueba como un cambio normal o lo devuelves con una
  corrección.
- Al aprobar, cada repositorio de una parte debe estar adoptado en el panel; si no, la
  aprobación se rechaza con «Adopta primero…» y no se crea nada.
- Un reparto **siempre lo aprueba una persona**: ni la autonomía del repositorio ni una
  receta lo aprueban solas, porque gasta cuota en otros repositorios.

**Qué ocurre al aprobar.** La tarea padre pasa a *Coordinando las partes* y el panel abre una
tarea por parte, en su repositorio, dentro de una iniciativa (las dependencias son las de
las iniciativas: una parte espera, también para investigar, a que la que necesita esté
**integrada**). Cada parte sigue su flujo normal: investiga bajo el `agentfarm.yaml` de su
repositorio, tú apruebas su plan y su PR, y la integras. Si su repositorio tiene autonomía hasta la PR, la parte la
hereda. Ninguna aprobación del padre sustituye a la del alcance de cada repositorio.

**Cierre.** Las PR de las partes dicen «Parte de `owner/forja#N`» y no llevan palabra de
cierre, porque la primera que se fusionara cerraría la issue antes de tiempo. La excepción
es la parte del repositorio integrador que depende de todas las demás (si Napoleon la
propone): su PR lleva `Closes #N`, y por construcción es la última. Cuando todas las partes
están integradas, el panel da el cambio por integrado. Si una parte se cancela, el cambio
sigue coordinando hasta que lo canceles o la rehagas.

**Cancelar.** *Cancelar el cambio completo* cancela las partes que aún no están integradas.
Las integradas se quedan: se deshacen revirtiéndolas una a una.

API: `GET /api/parts` (lectura abierta a las personas) lista las partes con el estado de
su tarea; el claim de una orden de investigación del integrador trae `repo_group`.
