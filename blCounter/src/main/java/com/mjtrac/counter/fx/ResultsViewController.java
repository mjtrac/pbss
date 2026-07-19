/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 *
 * Mirrors the removed ResultsController's DB-driven /results query (not the
 * session-tally-based /report — this is the durable view that still works
 * after a restart, since it queries persisted vote records). The
 * precinct/party breakdowns and the separate embedded rcv/scribble HTML
 * report panes are not exposed here — scope note for a follow-up pass.
 */
package com.mjtrac.counter.fx;

import com.mjtrac.counter.service.ResultsQueryService;
import com.mjtrac.counter.service.ResultsQueryService.VoteRow;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ResultsViewController {

    private final ResultsQueryService queryService;

    @FXML private VBox printableContent;
    @FXML private Label messageLabel;
    @FXML private Label totalBallotsLabel;
    @FXML private Label totalVotesLabel;
    @FXML private Label overvotedLabel;
    @FXML private Label scribbledLabel;
    @FXML private TableView<VoteRow> votesTable;

    public ResultsViewController(ResultsQueryService queryService) {
        this.queryService = queryService;
    }

    @FXML
    private void initialize() {
        hideMessage();
        buildColumns();
        refresh();
    }

    private void buildColumns() {
        TableColumn<VoteRow, String> contestCol = new TableColumn<>("Contest");
        contestCol.setCellValueFactory(new PropertyValueFactory<>("contest"));
        contestCol.setPrefWidth(220);

        TableColumn<VoteRow, String> candidateCol = new TableColumn<>("Candidate");
        candidateCol.setCellValueFactory(new PropertyValueFactory<>("candidate"));
        candidateCol.setPrefWidth(200);

        TableColumn<VoteRow, Number> votedCol = new TableColumn<>("Voted");
        votedCol.setCellValueFactory(row -> new javafx.beans.property.SimpleLongProperty(row.getValue().getVoted()));
        votedCol.setPrefWidth(90);

        TableColumn<VoteRow, Number> overvotedCol = new TableColumn<>("Overvoted");
        overvotedCol.setCellValueFactory(row -> new javafx.beans.property.SimpleLongProperty(row.getValue().getOvervoted()));
        overvotedCol.setPrefWidth(90);

        TableColumn<VoteRow, Number> unmarkedCol = new TableColumn<>("Unmarked");
        unmarkedCol.setCellValueFactory(row -> new javafx.beans.property.SimpleLongProperty(row.getValue().getUnmarked()));
        unmarkedCol.setPrefWidth(90);

        votesTable.getColumns().setAll(List.of(contestCol, candidateCol, votedCol, overvotedCol, unmarkedCol));
    }

    @FXML
    private void handleRefresh() {
        refresh();
    }

    /**
     * Prints the summary + votes table as currently shown — javafx.print
     * (not java.awt.print, used by the Swing/scanner-family batch sheets)
     * since printing an actual Node is the natural fit here and this app
     * has no AWT/Swing dependency to reuse.
     */
    @FXML
    private void handlePrintResults() {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            new Alert(Alert.AlertType.ERROR, "No printer available.").showAndWait();
            return;
        }
        Node node = printableContent;
        if (job.showPrintDialog(node.getScene().getWindow())) {
            boolean ok = job.printPage(node);
            if (ok) {
                job.endJob();
            } else {
                new Alert(Alert.AlertType.ERROR, "Printing failed.").showAndWait();
            }
        }
    }

    private void refresh() {
        try {
            votesTable.setItems(FXCollections.observableArrayList(queryService.votesByContest()));
            totalBallotsLabel.setText(String.valueOf(queryService.totalBallotImages()));
            totalVotesLabel.setText(String.valueOf(queryService.totalVotesCast()));
            overvotedLabel.setText(String.valueOf(queryService.totalOvervoted()));
            scribbledLabel.setText(String.valueOf(queryService.totalScribbled()));
            hideMessage();
        } catch (Exception e) {
            votesTable.setItems(FXCollections.observableArrayList());
            totalBallotsLabel.setText("0");
            totalVotesLabel.setText("0");
            overvotedLabel.setText("0");
            scribbledLabel.setText("0");
            showMessage("No scan results available yet. Run a count first.");
        }
    }

    private void showMessage(String text) {
        messageLabel.setText(text);
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void hideMessage() {
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);
    }
}
