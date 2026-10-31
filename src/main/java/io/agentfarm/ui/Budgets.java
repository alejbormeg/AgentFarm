package io.agentfarm.ui;

import io.agentfarm.core.Json;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Grid;
import io.mateu.uidl.data.Text;

@Title("Clover · presupuesto")
public class Budgets {
  @Label("Información")
  Text note =
      new Text(
          "La suscripción se mide como cuota; la API, en USD. La política de ejemplo reserva como"
              + " máximo el 50% semanal. Sin lectura vigente se pausa la admisión. El consumo del"
              + " proveedor todavía se registra manualmente.");

  @Label("Registro")
  Grid inventory =
      UiSupport.table(
          "accounts",
          UiSupport.budgets().accounts(),
          "account",
          "Cuenta",
          "mode",
          "Modalidad",
          "shares",
          "Asignación por ventana (puntos básicos)");

  @Label("Cuenta")
  String account = "codex-subscription";

  @Label("Máximo semanal (%)")
  int weeklyPercent = 50;

  @Label("Resultado de la consulta")
  @ReadOnly
  @Stereotype(FieldStereotype.textarea)
  String status = "";

  @Label("Configurar cuota semanal")
  @Button
  @Action
  public Budgets configure() {
    if (weeklyPercent < 0 || weeklyPercent > 100)
      throw new IllegalArgumentException("El porcentaje debe estar entre 0 y 100");
    UiSupport.budgets()
        .execute(
            "configure",
            Json.MAPPER.valueToTree(
                Json.obj(
                    "account",
                    account,
                    "mode",
                    "subscription",
                    "shares",
                    Json.obj("weekly", weeklyPercent * 100))),
            UiSupport.actor());
    return new Budgets();
  }

  @Label("Consultar saldo")
  @Toolbar
  @Action
  public void consult() {
    var result = UiSupport.budgets().status(account);
    @SuppressWarnings("unchecked")
    var reasons = (java.util.List<String>) result.get("reasons");
    status =
        result.get("state").equals("available")
            ? "Disponible para reservar."
            : "Admisión pausada: "
                + String.join("; ", reasons.stream().map(UiSupport::reason).toList());
  }

  @Label("Conciliación avanzada")
  Text advanced =
      new Text(
          "Las operaciones de ventana, observación y conciliación requieren unidades explícitas."
              + " 10000 puntos básicos = 100%; 1000000 unidades API = 1 USD. Una ventana utiliza el"
              + " reinicio real del proveedor.");

  enum Operation {
    @Label("Registrar ventana")
    open_window,
    @Label("Registrar consumo")
    observe,
    @Label("Reservar")
    reserve,
    @Label("Conciliar")
    settle
  }

  @Label("Operación de conciliación")
  Operation operation = Operation.observe;

  @Label("Datos de la operación (JSON)")
  @Stereotype(FieldStereotype.textarea)
  String operationData = "";

  @Label("Registrar operación")
  @Toolbar
  @Action(
      confirmationRequired = true,
      confirmationMessage =
          "Se registrarán estos datos en el presupuesto persistente. Comprueba cuenta, unidades y"
              + " reinicio del proveedor.")
  public void apply() {
    status =
        Json.encode(
            UiSupport.budgets()
                .execute(
                    operation.name().replace('_', '-'),
                    Json.parse(operationData),
                    UiSupport.actor()));
  }

  @Label("Actualizar")
  @Toolbar
  @Action
  public Budgets refresh() {
    return new Budgets();
  }
}
