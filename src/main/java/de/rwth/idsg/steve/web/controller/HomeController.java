package de.rwth.idsg.steve.web.controller;

import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.dto.ConnectorStatus;
import de.rwth.idsg.steve.service.ChargePointHelperService;
import de.rwth.idsg.steve.utils.ConnectorStatusFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.List;

/**
 *
 * @author Sevket Goekay <goekay@dbis.rwth-aachen.de>
 *
 */
@Controller
@RequestMapping(value = "/manager/home", method = RequestMethod.GET)
public class HomeController {

    @Autowired private ChargePointRepository chargePointRepository;
    @Autowired private ChargePointHelperService chargePointHelperService;

    // -------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------

    private static final String CONNECTOR_STATUS_PATH = "/connectorStatus";
    private static final String OCPP_JSON_STATUS = "/ocppJsonStatus";

    // -------------------------------------------------------------------------
    // HTTP methods
    // -------------------------------------------------------------------------

    @RequestMapping
    public String getHome(Model model) {
        model.addAttribute("stats", chargePointHelperService.getStats());
        return "home";
    }

    @RequestMapping(value = CONNECTOR_STATUS_PATH)
    public String getConnectorStatus(Model model) {
        List<ConnectorStatus> latestList = chargePointRepository.getChargePointConnectorStatus();
        List<ConnectorStatus> filteredList = ConnectorStatusFilter.filterAndPreferZero(latestList);
        model.addAttribute("connectorStatusList", filteredList);
        return "connectorStatus";
    }

    @RequestMapping(value = OCPP_JSON_STATUS)
    public String getOcppJsonStatus(Model model) {
        model.addAttribute("ocppJsonStatusList", chargePointHelperService.getOcppJsonStatus());
        return "ocppJsonStatus";
    }
}
