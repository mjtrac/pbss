/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewerui;

import com.mjtrac.viewer.service.BallotViewService;
import com.mjtrac.viewer.service.BallotViewService.BallotImageSummary;
import org.springframework.stereotype.Component;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BiConsumer;

/** Ballot list + filename filter — the Swing equivalent of viewer/index.html. */
@Component
class BallotListPanel extends JPanel {

    private final BallotViewService viewService;
    private final RowModel model = new RowModel();
    private final JTable table = new JTable(model);
    private final JTextField filterField = new JTextField(24);
    private final JLabel countLabel = new JLabel();

    private BiConsumer<Long, List<Long>> onView = (id, ids) -> {};

    BallotListPanel(BallotViewService viewService) {
        super(new BorderLayout(8, 8));
        this.viewService = viewService;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Filter (name/glob, e.g. *precinct3*):"));
        top.add(filterField);
        JButton filterBtn = new JButton("Filter");
        JButton clearBtn = new JButton("Clear");
        top.add(filterBtn);
        top.add(clearBtn);
        add(top, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(80);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(countLabel, BorderLayout.WEST);
        JButton viewBtn = new JButton("View →");
        bottom.add(viewBtn, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        filterBtn.addActionListener(e -> applyFilter());
        clearBtn.addActionListener(e -> { filterField.setText(""); refresh(); });
        filterField.addActionListener(e -> applyFilter());
        viewBtn.addActionListener(e -> viewSelected());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) viewSelected();
            }
        });
    }

    void setOnView(BiConsumer<Long, List<Long>> onView) {
        this.onView = onView;
    }

    /** Reloads the unfiltered full list — call whenever this panel becomes visible. */
    void refresh() {
        model.setRows(viewService.listAll());
        updateCount();
    }

    private void applyFilter() {
        String pattern = filterField.getText() == null ? "" : filterField.getText().trim();
        model.setRows(pattern.isEmpty() ? viewService.listAll() : viewService.listByGlob(pattern));
        updateCount();
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

    private static class RowModel extends AbstractTableModel {
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
        @Override public String getColumnName(int col) { return col == 0 ? "ID" : "Filename"; }
        @Override public Object getValueAt(int row, int col) {
            BallotImageSummary b = rows.get(row);
            return col == 0 ? b.id : b.imageName;
        }
    }
}
