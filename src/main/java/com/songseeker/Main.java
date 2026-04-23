package com.songseeker;

import java.util.List;

import atlantafx.base.theme.Dracula;
import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

public class Main extends Application {
    private final AIService aiService = new AIService();
    private final ObservableList<AIService.SongResult> results = FXCollections.observableArrayList();
    private final BooleanProperty searchInProgress = new SimpleBooleanProperty(false);

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        Application.setUserAgentStylesheet(new Dracula().getUserAgentStylesheet());

        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPromptText(Messages.get("field.apiKey.prompt"));
        String envApiKey = System.getenv("OPENAI_API_KEY");
        if (envApiKey != null && !envApiKey.isBlank()) {
            apiKeyField.setText(envApiKey);
        }

        TextField modelField = new TextField(AIService.DEFAULT_MODEL);
        modelField.setPromptText(Messages.get("field.model.prompt"));

        TextArea queryArea = new TextArea();
        queryArea.setPromptText(Messages.get("field.query.prompt"));
        queryArea.setWrapText(true);
        queryArea.setPrefRowCount(6);

        Button searchButton = new Button(Messages.get("button.search"));
        searchButton.setDefaultButton(true);

        ProgressIndicator progressIndicator = new ProgressIndicator();
        progressIndicator.setVisible(false);
        progressIndicator.setManaged(false);
        progressIndicator.setPrefSize(28, 28);

        Label statusLabel = new Label(Messages.get("status.idle"));
        statusLabel.setWrapText(true);

        TableView<AIService.SongResult> resultTable = createResultTable();
        resultTable.setItems(results);
        resultTable.setPlaceholder(new Label(Messages.get("table.placeholder")));

        searchButton.setOnAction(event -> runSearch(
                apiKeyField.getText(),
                modelField.getText(),
                queryArea.getText(),
                progressIndicator,
                statusLabel
        ));

        searchButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> searchInProgress.get() || apiKeyField.getText().isBlank() || queryArea.getText().isBlank(),
                searchInProgress,
                apiKeyField.textProperty(),
                queryArea.textProperty()
        ));

        Label heading = new Label(Messages.get("app.heading"));
        heading.setFont(Font.font("System", FontWeight.BOLD, 24));

        Label subheading = new Label(Messages.get("app.subheading"));
        subheading.setWrapText(true);

        HBox credentialsRow = new HBox(12,
                labelledBox(Messages.get("field.apiKey"), apiKeyField),
                labelledBox(Messages.get("field.model"), modelField)
        );
        HBox.setHgrow(credentialsRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(credentialsRow.getChildren().get(1), Priority.ALWAYS);

        HBox actionRow = new HBox(12, searchButton, progressIndicator);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox topContent = new VBox(14,
                heading,
                subheading,
                credentialsRow,
                labelledBox(Messages.get("field.query"), queryArea),
                actionRow,
                statusLabel
        );
        topContent.setPadding(new Insets(18));

        BorderPane root = new BorderPane();
        root.setTop(topContent);
        root.setCenter(resultTable);
        BorderPane.setMargin(resultTable, new Insets(0, 18, 18, 18));

        Scene scene = new Scene(root, 980, 700);
        primaryStage.setTitle(Messages.get("app.title"));
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private TableView<AIService.SongResult> createResultTable() {
        TableView<AIService.SongResult> table = new TableView<>();

        TableColumn<AIService.SongResult, String> titleColumn = new TableColumn<>(Messages.get("table.title"));
        titleColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().title()));
        titleColumn.setPrefWidth(220);

        TableColumn<AIService.SongResult, String> authorColumn = new TableColumn<>(Messages.get("table.author"));
        authorColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().author()));
        authorColumn.setPrefWidth(190);

        TableColumn<AIService.SongResult, String> genreColumn = new TableColumn<>(Messages.get("table.genre"));
        genreColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().genre()));
        genreColumn.setPrefWidth(140);

        TableColumn<AIService.SongResult, String> reasonColumn = new TableColumn<>(Messages.get("table.reason"));
        reasonColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().reasoning()));
        reasonColumn.setPrefWidth(320);

        TableColumn<AIService.SongResult, String> linkColumn = new TableColumn<>(Messages.get("table.link"));
        linkColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().link()));
        linkColumn.setPrefWidth(180);
        linkColumn.setCellFactory(column -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink();

            {
                link.setOnAction(event -> openLink(link.getText()));
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setGraphic(null);
                } else {
                    link.setText(item);
                    setGraphic(link);
                }
            }
        });

        table.getColumns().addAll(titleColumn, authorColumn, genreColumn, reasonColumn, linkColumn);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return table;
    }

    private VBox labelledBox(String labelText, javafx.scene.Node node) {
        Label label = new Label(labelText);
        VBox box = new VBox(6, label, node);
        VBox.setVgrow(node, Priority.ALWAYS);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private void runSearch(
            String apiKey,
            String model,
            String query,
            ProgressIndicator progressIndicator,
            Label statusLabel
    ) {
        aiService.setApiKey(apiKey);

        Task<List<AIService.SongResult>> searchTask = new Task<>() {
            @Override
            protected List<AIService.SongResult> call() throws Exception {
                return aiService.searchSongs(
                        model == null || model.isBlank() ? AIService.DEFAULT_MODEL : model.trim(),
                        query
                );
            }
        };

        searchInProgress.set(true);
        progressIndicator.setVisible(true);
        progressIndicator.setManaged(true);
        statusLabel.setText(Messages.get("status.searching"));

        searchTask.setOnSucceeded(event -> {
            results.setAll(searchTask.getValue());
            statusLabel.setText(Messages.format("status.searchSuccess", results.size()));
            restoreSearchButtonState(progressIndicator);
        });

        searchTask.setOnFailed(event -> {
            results.clear();
            Throwable error = searchTask.getException();
            statusLabel.setText(error == null ? Messages.get("status.searchFailed") : error.getMessage());
            restoreSearchButtonState(progressIndicator);
        });

        Thread thread = new Thread(searchTask, "song-search");
        thread.setDaemon(true);
        thread.start();
    }

    private void restoreSearchButtonState(ProgressIndicator progressIndicator) {
        searchInProgress.set(false);
        progressIndicator.setVisible(false);
        progressIndicator.setManaged(false);
    }

    private void openLink(String link) {
        getHostServices().showDocument(link);
    }
}
