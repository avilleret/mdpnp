package org.mdpnp.apps.testapp.validate;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;

import org.mdpnp.apps.testapp.vital.Vital;

public class Chart {
    @FXML protected StackedBarChart<String, Number> barChart;

    @FXML Button removeButton;
    @FXML BorderPane main;
    
    private Vital vital;
    private VitalValidator vitalValidator;
    
    public Vital getVital() {
        return vital;
    }
    public Button getRemoveButton() {
        return removeButton;
    }
    
    public void setModel(Vital v) {
       
        if(null != this.vital) {
            main.setCenter(null);
            barChart.titleProperty().unbind();
            barChart = null;
        }
        this.vital = v;
        if(null != v) {
            barChart = new StackedBarChart<>(new CategoryAxis(), new NumberAxis());
            this.vitalValidator = new VitalValidator(v, barChart.getData());
            barChart.setMinHeight(200.0);
            barChart.setAnimated(false);
//            validator.setData(barChart.getData());
//            barChart.getData().add(validator.getSeries());
            barChart.titleProperty().bind(v.labelProperty());
            main.setCenter(barChart);
            BorderPane.setAlignment(barChart, Pos.CENTER);
        }
    }

}