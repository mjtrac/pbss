/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.builderui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reusable list+form CRUD panel: a JTable of entities plus New/Edit/Delete
 * buttons, driving a JDialog-based form supplied by the subclass. Used by
 * every "simple" screen (Party, Jurisdiction, BallotType, Language, Region)
 * to avoid re-writing the same table+button wiring nine times — only the
 * column definitions, load/save/delete calls, and the form itself differ
 * per entity.
 */
abstract class SimpleCrudPanel<T> extends JPanel {

    private final RowModel model;
    private final JTable table;
    private final JPanel buttons;
    private boolean firstRefreshDone = false;

    SimpleCrudPanel(String title, String[] columnNames, Function<T, Object[]> rowValues) {
        this(title, columnNames, rowValues, title.replaceAll("[^A-Za-z0-9]", ""));
    }

    /**
     * @param idPrefix Robot-lookup prefix for this screen's shared table/New/Edit/Delete/Refresh
     *                 controls (e.g. "elections" -> "electionsTable", "electionsNewButton", ...).
     *                 Defaults to a sanitized `title` when not given explicitly, but panels whose
     *                 title contains spaces/punctuation that would collide or read awkwardly
     *                 (e.g. "Ballot Design Templates") should pass a shorter explicit prefix.
     */
    SimpleCrudPanel(String title, String[] columnNames, Function<T, Object[]> rowValues, String idPrefix) {
        super(new BorderLayout(8, 8));
        // columnNames/rowValues must be set before constructing the table:
        // JTable's constructor synchronously calls back into the table model
        // for getColumnCount(), which reads columnNames. A field initializer
        // for `table` would run before this constructor body, seeing null.
        this.columnNames = columnNames;
        this.rowValues = rowValues;
        this.model = new RowModel();
        this.table = new JTable(model);
        table.setName(idPrefix + "Table");
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(new JLabel(title), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton newBtn = new JButton("New");
        JButton editBtn = new JButton("Edit");
        JButton deleteBtn = new JButton("Delete");
        JButton refreshBtn = new JButton("Refresh");
        newBtn.setName(idPrefix + "NewButton");
        editBtn.setName(idPrefix + "EditButton");
        deleteBtn.setName(idPrefix + "DeleteButton");
        refreshBtn.setName(idPrefix + "RefreshButton");
        buttons.add(newBtn);
        buttons.add(editBtn);
        buttons.add(deleteBtn);
        buttons.add(refreshBtn);
        add(buttons, BorderLayout.SOUTH);

        newBtn.addActionListener(e -> openForm(null));
        editBtn.addActionListener(e -> {
            T sel = selected();
            if (sel != null) openForm(sel);
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    T sel = selected();
                    if (sel != null) openForm(sel);
                }
            }
        });
        deleteBtn.addActionListener(e -> {
            T sel = selected();
            if (sel == null) return;
            int confirm = JOptionPane.showConfirmDialog(this, "Delete this item?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                delete(sel);
                refresh();
            }
        });
        refreshBtn.addActionListener(e -> refresh());
    }

    private final String[] columnNames;
    private final Function<T, Object[]> rowValues;

    /** Lets a subclass add its own button(s) next to New/Edit/Delete/Refresh — e.g. a quick-setup shortcut. */
    protected final void addToolbarButton(JButton button) {
        buttons.add(button);
    }

    /**
     * Override to offer a one-time quick-setup dialog the very first time
     * this screen is opened with no existing rows (e.g. "Use Single
     * Party?"). Only fires once per panel instance, on the first refresh()
     * call, and only if the list was actually empty at that point.
     */
    protected void onFirstOpenEmpty() {}

    /** Called once after construction (subclass fields aren't set until then). */
    void refresh() {
        List<T> rows = loadAll();
        model.setRows(rows);
        if (!firstRefreshDone) {
            firstRefreshDone = true;
            if (rows.isEmpty()) onFirstOpenEmpty();
        }
    }

    private T selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.rows.get(row);
    }

    private void openForm(T existing) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), true);
        dialog.setTitle(existing == null ? "New" : "Edit");
        Consumer<T> onSave = entity -> {
            save(entity);
            dialog.dispose();
            refresh();
        };
        dialog.getContentPane().add(buildForm(existing, onSave));
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    abstract List<T> loadAll();
    abstract void save(T entity);
    abstract void delete(T entity);
    abstract JComponent buildForm(T existingOrNull, Consumer<T> onSave);

    /** Standard labeled-field-plus-Save/Cancel form shell — subclasses add fields to `fields`. */
    static JPanel formShell(String title, JPanel fields, Runnable onSave, Runnable onCancel) {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.add(new JLabel(title), BorderLayout.NORTH);
        root.add(fields, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.setName("saveButton");
        cancel.setName("cancelButton");
        save.addActionListener(e -> onSave.run());
        cancel.addActionListener(e -> onCancel.run());
        buttons.add(cancel);
        buttons.add(save);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    /**
     * Multi-line JTextArea with word-wrap enabled, for prose fields
     * (preamble/postamble/instructions/explanatory text) where the field
     * stays a fixed few rows tall but long text should wrap within that
     * width rather than requiring horizontal scrolling to read. Not used
     * for header HTML/CSS fields, where wrapping would obscure structure.
     */
    static JTextArea wrappingTextArea(String text, int rows, int cols) {
        JTextArea area = new JTextArea(text, rows, cols);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    static JPanel fieldGrid() {
        JPanel p = new JPanel(new GridBagLayout());
        return p;
    }

    static void addField(JPanel grid, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST;
        grid.add(new JLabel(label), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        grid.add(field, c);
    }

    /** Populates a JComboBox from a supplier and selects `current` if present, by identity-agnostic equality on the id. */
    static <E> void populateCombo(JComboBox<E> combo, Supplier<List<E>> loader) {
        combo.removeAllItems();
        for (E e : loader.get()) combo.addItem(e);
    }

    private class RowModel extends AbstractTableModel {
        private List<T> rows = List.of();

        void setRows(List<T> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columnNames.length; }
        @Override public String getColumnName(int col) { return columnNames[col]; }
        @Override public Object getValueAt(int row, int col) { return rowValues.apply(rows.get(row))[col]; }
    }
}
