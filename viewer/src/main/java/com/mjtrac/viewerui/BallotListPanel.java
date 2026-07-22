/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import com.mjtrac.counter.entity.BallotImage;
import com.mjtrac.counter.entity.BarcodeRecord;
import com.mjtrac.counter.entity.CandidateRecord;
import com.mjtrac.counter.entity.ContestRecord;
import com.mjtrac.counter.entity.VoteOpportunity;
import com.mjtrac.viewer.service.BallotViewService;
import com.mjtrac.viewer.service.BallotViewService.BallotImageSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.springframework.stereotype.Component;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Ballot list + filename filter — the Swing equivalent of viewer/index.html. */
@Component
class BallotListPanel extends JPanel {

    private final BallotViewService viewService;
    // showPathBox must be initialized before model/table — JTable's own
    // constructor calls setModel() -> tableChanged() ->
    // createDefaultColumnsFromModel(), which calls RowModel.getColumnName()
    // immediately, and that reads showPathBox. Field initializers run in
    // declaration order, so this one has to come first.
    private final JCheckBox showPathBox = new JCheckBox("Show full path");
    private final RowModel model = new RowModel();
    private final JTable table = new JTable(model);
    private final JTextField nameFilterField = new JTextField(20);
    private final JTextField sqlFilterField = new JTextField(28);
    private final JTextField selectedPathField = new JTextField();
    private final JLabel countLabel = new JLabel();

    private BiConsumer<Long, List<Long>> onView = (id, ids) -> {};

    BallotListPanel(BallotViewService viewService) {
        super(new BorderLayout(8, 8));
        this.viewService = viewService;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Two separate filter entries — matching viewer/index.html's
        // Name-filter/SQL-filter tab pair, but as two always-visible rows
        // rather than tabs, since Swing has no need to hide either behind a
        // tab click here.
        JPanel filters = new JPanel();
        filters.setLayout(new BoxLayout(filters, BoxLayout.Y_AXIS));

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        nameRow.add(new JLabel("Name filter:"));
        nameRow.add(nameFilterField);
        JButton nameFilterBtn = new JButton("Filter by Name");
        nameRow.add(nameFilterBtn);
        nameFilterField.setToolTipText("Filename/path glob — * and ? wildcards, e.g. *precinct3*");
        filters.add(nameRow);

        JPanel sqlRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sqlRow.add(new JLabel("SQL filter:"));
        sqlRow.add(sqlFilterField);
        JButton sqlHelpBtn = new JButton("?");
        sqlHelpBtn.setToolTipText("SQL filter help — columns and examples");
        sqlRow.add(sqlHelpBtn);
        JButton sqlFilterBtn = new JButton("Filter by SQL");
        sqlRow.add(sqlFilterBtn);
        JButton clearBtn = new JButton("Clear");
        sqlRow.add(clearBtn);
        // Same aliases/keyword-rejection as viewer/index.html's SQL panel,
        // run through BallotViewService.listBySql() — the same shared,
        // already-reviewed method bCounter's/blCounter's web viewer use,
        // not a separate reimplementation.
        sqlFilterField.setToolTipText("SQL WHERE clause — e.g. c.contest_title = 'Mayor'");
        filters.add(sqlRow);

        JPanel pathRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pathRow.add(showPathBox);
        filters.add(pathRow);

        add(filters, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(80);

        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.add(new JScrollPane(table), BorderLayout.CENTER);
        // Always shows the SELECTED row's full path (regardless of the
        // Filename/Path column toggle above) — a real JTextField rather
        // than a JLabel so its text can be drag-selected and Ctrl+C'd,
        // matching the SQL help dialog's JEditorPane approach.
        selectedPathField.setEditable(false);
        selectedPathField.setDragEnabled(true);
        selectedPathField.setBorder(BorderFactory.createTitledBorder("Selected path"));
        center.add(selectedPathField, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(countLabel, BorderLayout.WEST);
        JButton viewBtn = new JButton("View →");
        bottom.add(viewBtn, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        nameFilterBtn.addActionListener(e -> applyNameFilter());
        nameFilterField.addActionListener(e -> applyNameFilter());
        sqlFilterBtn.addActionListener(e -> applySqlFilter());
        sqlFilterField.addActionListener(e -> applySqlFilter());
        sqlHelpBtn.addActionListener(e -> showSqlHelp());
        clearBtn.addActionListener(e -> {
            nameFilterField.setText("");
            sqlFilterField.setText("");
            refresh();
        });
        showPathBox.addActionListener(e -> {
            table.getColumnModel().getColumn(1).setHeaderValue(showPathBox.isSelected() ? "Path" : "Filename");
            table.getTableHeader().repaint();
            model.fireTableDataChanged();
        });
        viewBtn.addActionListener(e -> viewSelected());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) viewSelected();
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            selectedPathField.setText(row < 0 ? "" : model.rows.get(row).imagePath);
            selectedPathField.setCaretPosition(0);
        });
    }

    boolean isShowFullPath() { return showPathBox.isSelected(); }

    /** Alias -> entity class, matching the aliases BallotViewService.listBySql()'s query already uses (bi/b/c/cdr/vo). */
    private static final Map<String, Class<?>> SCHEMA_ALIASES = new LinkedHashMap<>();
    static {
        SCHEMA_ALIASES.put("bi", BallotImage.class);
        SCHEMA_ALIASES.put("b", BarcodeRecord.class);
        SCHEMA_ALIASES.put("c", ContestRecord.class);
        SCHEMA_ALIASES.put("cdr", CandidateRecord.class);
        SCHEMA_ALIASES.put("vo", VoteOpportunity.class);
    }

    /**
     * Builds the SQL filter help dialog's column reference straight from the
     * @Entity classes' @Column/@JoinColumn annotations rather than a
     * hand-maintained list — there's no separate schema.sql in this repo,
     * so the entity annotations *are* the actual schema, and reading them
     * here means this reference can't go stale as entities change.
     */
    private static String buildSchemaHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:sans-serif;font-size:11px;width:450px'>");
        sb.append("<p><b>SQL filter</b> — enter a WHERE clause fragment (no <tt>SELECT</tt>/<tt>;</tt>/DML/DDL).</p>");
        sb.append("<p style='margin-top:8px'><b>Available columns</b> <i>(read from the entity schema)</i></p>");
        sb.append("<p style='font-family:monospace'>");
        boolean first = true;
        for (Map.Entry<String, Class<?>> entry : SCHEMA_ALIASES.entrySet()) {
            if (!first) sb.append("<br>");
            first = false;
            Class<?> type = entry.getValue();
            Table table = type.getAnnotation(Table.class);
            String tableName = table != null ? table.name() : type.getSimpleName();
            sb.append("<b>").append(tableName).append(" (").append(entry.getKey()).append("):</b> ");
            // Plain ", " (not &nbsp;) so the HTML renderer can wrap between
            // columns — the full reflected column list runs much longer
            // per table than the old curated one did.
            sb.append(String.join(", ", columnNames(type)));
        }
        sb.append("</p>");
        sb.append("<p style='margin-top:8px'><b>Examples</b> <i>(select text below and drag or Ctrl+C it into the field)</i></p>");
        sb.append("<p style='font-family:monospace'>");
        sb.append("c.contest_title = 'Mayor'<br>");
        sb.append("vo.vote_status = 'VOTED' AND c.contest_title = 'City Council'");
        sb.append("</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Real column names for one entity: @Column/@JoinColumn's name(), falling back to the field name (snake_cased) when unset. */
    private static List<String> columnNames(Class<?> entityType) {
        List<String> names = new ArrayList<>();
        for (Field field : entityType.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            if (column != null) {
                names.add(column.name().isEmpty() ? camelToSnake(field.getName()) : column.name());
            } else if (joinColumn != null) {
                names.add(joinColumn.name().isEmpty() ? camelToSnake(field.getName()) + "_id" : joinColumn.name());
            } else if (field.isAnnotationPresent(Id.class)) {
                names.add(camelToSnake(field.getName()));
            }
        }
        return names;
    }

    private static String camelToSnake(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private void showSqlHelp() {
        // JEditorPane (not JLabel) so the reference text is selectable —
        // Ctrl+C copy and drag-out both come for free from JTextComponent,
        // and sqlFilterField accepts the drop/paste with no extra wiring
        // since JTextField's default TransferHandler already imports
        // Strings.
        JEditorPane helpPane = new JEditorPane("text/html", buildSchemaHtml());
        helpPane.setEditable(false);
        helpPane.setDragEnabled(true);
        helpPane.setBackground(UIManager.getColor("Panel.background"));
        helpPane.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(helpPane);
        scroll.setPreferredSize(new Dimension(480, 440));
        JOptionPane.showMessageDialog(this, scroll, "SQL Filter Help", JOptionPane.PLAIN_MESSAGE);
    }

    void setOnView(BiConsumer<Long, List<Long>> onView) {
        this.onView = onView;
    }

    /** Reloads the unfiltered full list — call whenever this panel becomes visible. */
    void refresh() {
        model.setRows(viewService.listAll());
        updateCount();
    }

    private void applyNameFilter() {
        String pattern = nameFilterField.getText() == null ? "" : nameFilterField.getText().trim();
        if (pattern.isEmpty()) {
            model.setRows(viewService.listAll());
            updateCount();
            return;
        }
        model.setRows(viewService.listByGlob(pattern));
        updateCount();
    }

    private void applySqlFilter() {
        String clause = sqlFilterField.getText() == null ? "" : sqlFilterField.getText().trim();
        if (clause.isEmpty()) {
            model.setRows(viewService.listAll());
            updateCount();
            return;
        }
        try {
            model.setRows(viewService.listBySql(clause));
            updateCount();
        } catch (IllegalArgumentException e) {
            // Same rejection BallotViewService.listBySql() already applies
            // (DML/DDL keywords, invalid SQL) — shown here rather than
            // silently falling back, matching viewer/index.html's
            // filterError handling.
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid Filter", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateCount() {
        countLabel.setText(model.getRowCount() + " ballot image" + (model.getRowCount() == 1 ? "" : "s"));
    }

    private void viewSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        BallotImageSummary selected = model.rows.get(row);
        onView.accept(selected.id, model.orderedIds());
    }

    /** Non-static so getColumnName()/getValueAt() can read the outer showPathBox toggle directly. */
    private class RowModel extends AbstractTableModel {
        private List<BallotImageSummary> rows = List.of();

        void setRows(List<BallotImageSummary> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        List<Long> orderedIds() {
            return rows.stream().map(r -> r.id).toList();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int col) { return col == 0 ? "ID" : (showPathBox.isSelected() ? "Path" : "Filename"); }
        @Override public Object getValueAt(int row, int col) {
            BallotImageSummary b = rows.get(row);
            return col == 0 ? b.id : (showPathBox.isSelected() ? b.imagePath : b.imageName);
        }
    }
}
