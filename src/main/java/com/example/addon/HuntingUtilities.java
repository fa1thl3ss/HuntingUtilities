package com.example.addon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.addon.hud.DuraPanelHUD;
import com.example.addon.hud.InfoAssistantHud;
import com.example.addon.hud.LootLensHud;
import com.example.addon.hud.MobanomHUD;
import com.example.addon.hud.NeighbourhoodWatchHUD;
import com.example.addon.hud.PortalStockHud;
import com.example.addon.hud.PortalTrackerHud;
import com.example.addon.hud.PositionHud;
import com.example.addon.hud.RocketPilotHud;
import com.example.addon.hud.SecondLifeHUD;
import com.example.addon.hud.ServerReportHUD;
import com.example.addon.hud.StatisticsInformation;
import com.example.addon.hud.TimeThrottleHUD;
import com.example.addon.modules.DungeonAssistant;
import com.example.addon.modules.ElytraAssistant;
import com.example.addon.modules.EndSafe;
import com.example.addon.modules.Graveyard;
import com.example.addon.modules.Handmold;
import com.example.addon.modules.Illushine;
import com.example.addon.modules.Inventory101;
import com.example.addon.modules.LavaMarker;
import com.example.addon.modules.LootLens;
import com.example.addon.modules.Mendbot;
import com.example.addon.modules.Mobanom;
import com.example.addon.modules.NeighbourhoodWatch;
import com.example.addon.modules.PearlPulse;
import com.example.addon.modules.PortalMaker;
import com.example.addon.modules.PortalTracker;
import com.example.addon.modules.RocketPilot;
import com.example.addon.modules.ServerHealthcareSystem;
import com.example.addon.modules.SignScanner;
import com.example.addon.modules.ThirdSight;
import com.example.addon.modules.Timethrottle;
import com.example.addon.modules.Tunnelers;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class HuntingUtilities extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger(HuntingUtilities.class);
    public static final Category CATEGORY = new Category("Hunting Utilities");
    public static final HudGroup HUD_GROUP = new HudGroup("Hunting Utilities");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Hunting Utilities");

        // Modules
        Modules modules = Modules.get();
        modules.add(new DungeonAssistant());
        modules.add(new ElytraAssistant());
        modules.add(new EndSafe());
        modules.add(new Graveyard());
        modules.add(new Inventory101());
        modules.add(new Illushine());
        modules.add(new LavaMarker());
        modules.add(new LootLens());
        modules.add(new PortalMaker());
        modules.add(new PortalTracker());
        modules.add(new RocketPilot());
        modules.add(new ServerHealthcareSystem());
        modules.add(new SignScanner());
        modules.add(new Timethrottle());
        modules.add(new Mobanom());
        modules.add(new NeighbourhoodWatch());
        modules.add(new Tunnelers());
        modules.add(new ThirdSight());
        modules.add(new Handmold());
        modules.add(new Mendbot());
        modules.add(new PearlPulse());

        // HUD elements
        Hud.get().register(StatisticsInformation.INFO);
        Hud.get().register(PortalTrackerHud.INFO);
        Hud.get().register(PortalStockHud.INFO);
        Hud.get().register(LootLensHud.INFO);
        Hud.get().register(PositionHud.INFO);
        Hud.get().register(RocketPilotHud.INFO);
        Hud.get().register(InfoAssistantHud.INFO);
        Hud.get().register(TimeThrottleHUD.INFO);
        Hud.get().register(MobanomHUD.INFO);
        Hud.get().register(NeighbourhoodWatchHUD.INFO);
        Hud.get().register(DuraPanelHUD.INFO);
        Hud.get().register(ServerReportHUD.INFO);
        Hud.get().register(SecondLifeHUD.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}