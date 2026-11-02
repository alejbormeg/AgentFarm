---
name: java-spring
description: Conventions for maintainable Java and Spring code and tests.
---
- Inyección por constructor con campos final. Nada de @Autowired en campos.
- Usa record para datos inmutables y Optional en retornos que pueden faltar, no en campos ni parámetros.
- Controladores finos: la lógica vive en servicios que se pueden probar sin Spring.
- @Transactional en el servicio, consciente de que no se aplica en llamadas internas ni a métodos privados.
- No captures Exception para ocultarla. Las excepciones llevan contexto y se traducen en un único punto.
- Cierra recursos con try-with-resources. Vigila las consultas N+1.
- JUnit 5 y AssertJ o los asserts del proyecto. Unitarias sin contexto de Spring; @SpringBootTest solo si la prueba necesita el cableado real.
- Para integración con bases de datos, usa el motor real o Testcontainers si el proyecto ya lo hace.
- Usa el wrapper del proyecto (./mvnw o ./gradlew) y respeta la versión de Java que declara.
