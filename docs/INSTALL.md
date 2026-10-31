# Instalar AgentFarm

## Docker Compose

Requisitos: Docker Engine + Compose v2, o Docker Desktop. Se desarrolla y verifica
en Ubuntu 24.04 LTS WSL, distribución Ubuntu.

1. Activa la integración de Docker Desktop con Ubuntu, si no lo está.
2. Clona el proyecto en Linux, por ejemplo /home/alex/projects/AgentFarm.
3. Ejecuta ./agentfarm up (equivale a docker compose up --build --wait -d).
4. Ejecuta ./agentfarm password y entra en http://localhost:8080 como admin.

La contraseña inicial se genera aleatoriamente y se guarda con permisos 0600.
No se imprime en el log. No hay contraseña universal. El volumen farm-data
conserva el registro y la contraseña entre reinicios. El primer arranque descarga
imágenes, dependencias y compila; no requiere Java, Maven, Python ni Node en el host.

El contenedor se ejecuta como UID/GID 10001, con raíz de solo lectura y límites
de memoria, CPU y PID. No monta socket Docker ni proyectos. La biblioteca nativa
de SQLite está incluida en la imagen y no se carga desde /tmp.

El puerto se publica solo en 127.0.0.1:8080. Esta consola es local: el gobierno
remoto requiere una entrega posterior con TLS e identidades por nodo.

## JAR sin Docker

Instala Java 21 y ejecuta java -jar agentfarm.jar. Para obtener el JAR desde
fuentes: ./mvnw verify. Maven Wrapper descarga la versión fijada.

Datos: ./data/agentfarm.db. Contraseña: ./data/admin-password.txt. Usuario: admin.

| Variable | Uso |
| --- | --- |
| AGENTFARM_DB | Ruta SQLite; por defecto ./data/agentfarm.db. |
| PORT | Puerto, por defecto 8080. |
| AGENTFARM_PASSWORD | Contraseña administrada por el operador, mínimo 16 caracteres. |
| AGENTFARM_BIND | Dirección de escucha; el JAR usa loopback. |

Con AGENTFARM_PASSWORD no se utiliza el archivo inicial. No guardes credenciales
en Git. La protección de origen del panel solo admite el host local.

## Actualizar

Detén el servicio y realiza copia consistente del volumen. Actualiza el código
y ejecuta ./agentfarm up. No borres el volumen para actualizar. El arranque
crea tablas que faltan y conserva las existentes.

No ejecutes Python y Java escribiendo los mismos archivos. La versión Python
se conserva en el historial Git, no como runtime de esta distribución.

## Problemas habituales

- Docker no responde: inicia Docker Desktop y verifica la integración con Ubuntu.
- Puerto ocupado: cambia el puerto del host en Compose o PORT para el JAR.
- Acceso: usa la contraseña de esta instalación, no la de otro volumen.
- Cuota pausada: registra ventanas y observaciones vigentes, sin borrar consumo.
- JAR accesible solo desde WSL: comprueba el reenvío localhost de tu WSL.
  Docker Desktop publica el puerto en Windows.
