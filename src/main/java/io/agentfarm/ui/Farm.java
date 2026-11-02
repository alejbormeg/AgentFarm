package io.agentfarm.ui;

import io.mateu.uidl.annotations.App;
import io.mateu.uidl.annotations.FavIcon;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Logo;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.interfaces.HomeRouteSupplier;

@UI(value = "", indexHtmlPath = "/static/agentfarm.html")
@App(themeToggle = false)
@Title("AgentFarm")
@Logo("/icons/napoleon.svg")
@FavIcon("/icons/napoleon.svg")
public class Farm implements HomeRouteSupplier {
  @Menu
  @Label("Bandeja")
  Overview overview;

  @Menu
  @Label("Tareas")
  Tasks tasks;

  @Menu
  @Label("Agentes")
  Agents agents;

  @Menu
  @Label("Presupuesto")
  Budgets budgets;

  @Menu
  @Label("Gobierno")
  Governance governance;

  @Override
  public String homeRoute() {
    return "overview";
  }
}
