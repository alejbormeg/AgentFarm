---
name: go
description: Conventions for maintainable Go code and tests.
---
- Devuelve errores con contexto usando %w y compruébalos con errors.Is o errors.As. Nada de panic para errores esperados.
- context.Context como primer parámetro en lo que hace E/S o puede cancelarse.
- Toda goroutine tiene un dueño y una forma de terminar. Prueba con -race.
- Interfaces pequeñas, definidas donde se consumen.
- gofmt y go vet sin avisos.
- Pruebas por tabla con t.Run, t.TempDir y t.Cleanup. net/http/httptest para integración HTTP.
