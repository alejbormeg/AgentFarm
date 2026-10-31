package io.agentfarm.ui;

import io.mateu.uidl.annotations.App;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.interfaces.HomeRouteSupplier;

@UI(value = "", indexHtmlPath = "/static/agentfarm.html")
@App(themeToggle = false)
@Title("AgentFarm")
public class Farm implements HomeRouteSupplier {
  @Menu
  @Label("Panorama")
  Overview overview;

  @Menu
  @Label("Nodos")
  Nodes nodes;

  @Menu
  @Label("Boxer")
  Workers workers;

  @Menu
  @Label("Tareas")
  Tasks tasks;

  @Menu
  @Label("Clover")
  Budgets budgets;

  @Menu
  @Label("Gobierno")
  Governance governance;

  @Override
  public String homeRoute() {
    return "overview";
  }
}
