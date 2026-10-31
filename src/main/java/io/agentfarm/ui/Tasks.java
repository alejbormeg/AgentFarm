package io.agentfarm.ui;

import io.agentfarm.core.Json;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Grid;
import io.mateu.uidl.data.Text;

@Title("Tareas y progreso")
public class Tasks {
  @Label("Información")
  Text note =
      new Text(
          "El progreso es declarado por el trabajador; vacío significa desconocido. «Review» en una"
              + " simulación no significa código implementado. La asignación reserva presupuesto y"
              + " espera un trabajador externo.");

  @Label("Registro")
  Grid inventory =
      UiSupport.table(
          "tasks",
          UiSupport.tasks().tasks(),
          "project",
          "Proyecto",
          "id",
          "Tarea",
          "state",
          "Estado",
          "worker",
          "Boxer",
          "progress",
          "Progreso declarado (%)",
          "cancel_requested",
          "Cancelación solicitada",
          "candidate_sha",
          "Commit candidato");

  @Label("Especificación de la tarea (JSON)")
  @Stereotype(FieldStereotype.textarea)
  String specification =
      "{\"id\":\"DEMO-001\",\"project\":\"example\",\"title\":\"Revisar"
          + " documentación\",\"acceptance\":[\"Informe"
          + " verificable\"],\"allowed_paths\":[\"docs/\"]}";

  @Label("Proyecto")
  String project;

  @Label("Identificador de tarea")
  String taskId;

  @Label("Boxer asignado")
  String workerId;

  @Label("Cuenta de presupuesto")
  String budgetAccount;

  @Label("ID único de ejecución")
  String runId;

  @Label("Cotas por ventana (unidades de la cuenta)")
  @Stereotype(FieldStereotype.textarea)
  String maximums = "{\"weekly\":100}";

  @Label("Encolar tarea")
  @Button
  @Action
  public Tasks enqueue() {
    UiSupport.tasks().enqueue(Json.parse(specification), UiSupport.actor());
    return new Tasks();
  }

  @Label("Simular una tarea")
  @Toolbar
  @Action
  public String simulate() {
    return Json.encode(UiSupport.tasks().simulate(2, 5, UiSupport.actor()));
  }

  @Label("Asignar con reserva")
  @Button(group = "Tarea existente")
  @Action
  public String assign() {
    return Json.encode(
        UiSupport.control()
            .assign(
                Json.MAPPER.valueToTree(
                    Json.obj(
                        "project",
                        project,
                        "id",
                        taskId,
                        "worker",
                        workerId,
                        "account",
                        budgetAccount,
                        "run_id",
                        runId,
                        "amounts",
                        Json.parse(maximums))),
                UiSupport.actor()));
  }

  @Label("Solicitar cancelación")
  @Button(group = "Tarea existente")
  @Action(
      confirmationRequired = true,
      confirmationMessage =
          "La tarea lista se cancelará; si ya fue asignada se solicitará cancelación. La reserva se"
              + " conserva hasta conocer el consumo real.")
  public Tasks cancel() {
    UiSupport.control().cancel(project, taskId, UiSupport.actor());
    return new Tasks();
  }

  @Label("Actualizar")
  @Toolbar
  @Action
  public Tasks refresh() {
    return new Tasks();
  }
}
