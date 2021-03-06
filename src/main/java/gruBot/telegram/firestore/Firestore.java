package gruBot.telegram.firestore;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import gruBot.telegram.bot.GruBot;
import gruBot.telegram.bot.GruBotConfig;
import gruBot.telegram.bot.GruBotPatterns;
import gruBot.telegram.logger.Logger;
import gruBot.telegram.objects.Group;
import org.telegram.telegrambots.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.api.objects.Message;
import org.telegram.telegrambots.api.objects.Update;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Firestore {
    private com.google.cloud.firestore.Firestore db;
    private GruBot bot;

    public Firestore(GruBot bot) {
        FirestoreOptions firestoreOptions =
                FirestoreOptions.getDefaultInstance().toBuilder()
                        .setProjectId(GruBotConfig.PROJECT_ID)
                        .build();
        this.db = firestoreOptions.getService();
        this.bot = bot;
        setPollUpdatesListener();
    }

    private void setPollUpdatesListener() {
        Logger.log("Setting polls update listener...", Logger.INFO);
        Query pollsQuery = db.collection("votes");
        pollsQuery.addSnapshotListener((snapshots, error) -> {
            if (error == null) {
                for (DocumentChange dc : snapshots.getDocumentChanges()) {
                    switch (dc.getType()) {
                        case ADDED:
                            break;
                        case MODIFIED:
                            DocumentSnapshot document = dc.getDocument();
                            EditMessageText editMessageText = getMessageText(document)
                                    .setChatId(Long.valueOf(document.get("group").toString()))
                                    .setMessageId(Integer.valueOf(document.get("messageId").toString()));
                            bot.updatePoll(editMessageText);
                            break;
                        case REMOVED:
                            break;
                        default:
                            break;
                    }
                }
            }
        });
    }

    public boolean checkGroupExists(long chatId) throws ExecutionException, InterruptedException {
        Logger.log("Checking group exists in database...", Logger.INFO);
        Query groupsQuery = db.collection("groups").whereEqualTo("chatId", chatId);
        ApiFuture<QuerySnapshot> snapshotApiFuture = groupsQuery.get();

        List<QueryDocumentSnapshot> documents = snapshotApiFuture.get().getDocuments();

        Logger.log("Group exists - " + !documents.isEmpty(), Logger.INFO);
        return !documents.isEmpty();
    }

    @SuppressWarnings("unchecked")
    public void checkUserExistsInGroup(Update update) throws ExecutionException, InterruptedException, NullPointerException {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();

        Logger.log("Checking user group relations...", Logger.INFO);
        Query groupsQuery = db.collection("groups").whereEqualTo("chatId", chatId);
        ApiFuture<QuerySnapshot> snapshotApiFuture = groupsQuery.get();
        List<QueryDocumentSnapshot> documents = snapshotApiFuture.get().getDocuments();
        for (DocumentSnapshot document : documents) {
            Map<String, Boolean> users = (Map<String, Boolean>) document.get("users");
            Boolean value = users.get(String.valueOf(userId));

            if (value == null || (value != null && value == false)) {
                Logger.log("Adding user to the group", Logger.INFO);
                addUserToGroup(groupsQuery, userId);
            } else {
                Logger.log("User is already in the group", Logger.INFO);
            }
        }
    }

    private void addUserToGroup(Query groupsQuery, long userId) throws ExecutionException, InterruptedException, NullPointerException {
        ApiFuture<QuerySnapshot> snapshotApiFuture = groupsQuery.get();
        List<QueryDocumentSnapshot> documents = snapshotApiFuture.get().getDocuments();
        for (DocumentSnapshot document : documents) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("users." + userId, true);

            document.getReference().update(updates);
            Logger.log("Group users updated", Logger.INFO);
        }
    }

    public void createNewGroup(Update update) {
        Logger.log("Creating new group...", Logger.INFO);
        Message message = update.getMessage();
        long chatId = message.getChatId();
        String chatName = message.getChat().getTitle();

        Group group = new Group(chatId, chatName, (new HashMap<>()));

        HashMap<String, Object> groupMap = new HashMap<>();
        groupMap.put("chatId", group.getId());
        groupMap.put("name", group.getName());
        groupMap.put("users", group.getUsers());

        db.collection("groups").add(groupMap);
        Logger.log("Group created...", Logger.INFO);
    }

    @SuppressWarnings("unchecked")
    public HashMap<String, Object> createNewAnnouncement(Update update) {
        Logger.log("Creating new announcement...", Logger.INFO);
        Message message = update.getMessage();
        long chatId = message.getChatId();

        Logger.log("Matching title to regexp...", Logger.INFO);
        Matcher m = Pattern.compile(GruBotPatterns.announcementTitle, Pattern.MULTILINE).matcher(message.getText());
        String announcementTitle = "";
        if (m.find()) {
            announcementTitle = m.group(0).replace("!", "");
            Logger.log("Title match is found", Logger.INFO);
        } else {
            Logger.log("Title match is not found", Logger.WARNING);
        }

        Logger.log("Matching text to regexp...", Logger.INFO);
        m = Pattern.compile(GruBotPatterns.announcementText, Pattern.MULTILINE).matcher(message.getText());
        String announcementText = "";
        if (m.find()) {
            announcementText = m.group(0);
            Logger.log("Text match is found", Logger.INFO);
        } else {
            Logger.log("Text match is not found", Logger.WARNING);
        }

        Logger.log("Matching finished", Logger.INFO);
        Logger.log("Getting group users...", Logger.INFO);

        Map<String, Boolean> groupUsers = new HashMap<>();
        try {
            Query groupsQuery = db.collection("groups").whereEqualTo("chatId", chatId);
            ApiFuture<QuerySnapshot> snapshotApiFuture = groupsQuery.get();

            List<QueryDocumentSnapshot> documents = snapshotApiFuture.get().getDocuments();
            for (DocumentSnapshot document : documents) {
                groupUsers = (Map<String, Boolean>) document.get("users");
            }

        } catch (Exception e) {
            Logger.log(e.getMessage(), Logger.ERROR);
        }

        Logger.log("Creating announcement...", Logger.INFO);
        HashMap<String, Object> announcement = new HashMap<>();
        announcement.put("group", message.getChatId());
        announcement.put("groupName", message.getChat().getTitle());
        announcement.put("messageId", -1);
        announcement.put("author", message.getFrom().getId());
        announcement.put("authorName", message.getFrom().getFirstName() + " " + message.getFrom().getLastName());
        announcement.put("desc", announcementTitle);
        announcement.put("date", new Date());
        announcement.put("type", "TELEGRAM");
        announcement.put("text", announcementText);
        HashMap<String, String> users = new HashMap<>();
        for (Map.Entry<String, Boolean> user : groupUsers.entrySet())
            users.put(user.getKey(), "new");
        announcement.put("users", users);

        ApiFuture<DocumentReference> reference = db.collection("announcements").add(announcement);
        announcement.put("reference", reference);
        Logger.log("Announcement created", Logger.INFO);
        return announcement;
    }

    @SuppressWarnings("unchecked")
    public HashMap<String, Object> createNewArticle(Update update) {
        Logger.log("Creating new article...", Logger.INFO);
        Message message = update.getMessage();
        long chatId = message.getChatId();

        Logger.log("Matching title to regexp...", Logger.INFO);
        Matcher m = Pattern.compile(GruBotPatterns.articleTitle, Pattern.MULTILINE).matcher(message.getText());
        String announcementTitle = "";
        if (m.find()) {
            announcementTitle = m.group(0).replace("*", "");
            Logger.log("Title match is found", Logger.INFO);
        } else {
            Logger.log("Title match is not found", Logger.WARNING);
        }

        Logger.log("Matching text to regexp...", Logger.INFO);
        m = Pattern.compile(GruBotPatterns.articleText, Pattern.MULTILINE).matcher(message.getText());
        String announcementText = "";
        if (m.find()) {
            announcementText = m.group(0);
            Logger.log("Text match is found", Logger.INFO);
        } else {
            Logger.log("Text match is not found", Logger.WARNING);
        }

        Logger.log("Matching finished", Logger.INFO);
        Logger.log("Getting group users...", Logger.INFO);

        Map<String, Boolean> groupUsers = new HashMap<>();
        try {
            Query groupsQuery = db.collection("groups").whereEqualTo("chatId", chatId);
            ApiFuture<QuerySnapshot> snapshotApiFuture = groupsQuery.get();

            List<QueryDocumentSnapshot> documents = snapshotApiFuture.get().getDocuments();
            for (DocumentSnapshot document : documents) {
                groupUsers = (Map<String, Boolean>) document.get("users");
            }

        } catch (Exception e) {
            Logger.log(e.getMessage(), Logger.ERROR);
        }

        Logger.log("Creating article...", Logger.INFO);
        HashMap<String, Object> article = new HashMap<>();
        article.put("group", message.getChatId());
        article.put("groupName", message.getChat().getTitle());
        article.put("messageId", -1);
        article.put("author", message.getFrom().getId());
        article.put("authorName", message.getFrom().getFirstName() + " " + message.getFrom().getLastName());
        article.put("desc", announcementTitle);
        article.put("date", new Date());
        article.put("type", "TELEGRAM");
        article.put("text", announcementText);
        HashMap<String, String> users = new HashMap<>();
        for (Map.Entry<String, Boolean> user : groupUsers.entrySet())
            users.put(user.getKey(), "new");
        article.put("users", users);

        ApiFuture<DocumentReference> reference = db.collection("articles").add(article);
        article.put("reference", reference);
        Logger.log("Article created", Logger.INFO);
        return article;
    }

    @SuppressWarnings("unchecked")
    public HashMap<String, Object> createNewPoll(Update update) {
        Logger.log("Creating new poll...", Logger.INFO);
        Message message = update.getMessage();
        long chatId = message.getChatId();

        Logger.log("Matching title to regexp...", Logger.INFO);
        Matcher m = Pattern.compile(GruBotPatterns.voteTitle, Pattern.MULTILINE).matcher(message.getText());
        String voteTitle = "";
        if(m.find()) {
            voteTitle = m.group(0).replace("?", "").replaceAll("\r", "");
            Logger.log("Title match is found", Logger.INFO);
        } else {
            Logger.log("Title match is not found", Logger.WARNING);
        }

        Logger.log("Matching text to regexp...", Logger.INFO);
        m = Pattern.compile(GruBotPatterns.voteText, Pattern.MULTILINE).matcher(message.getText());

        HashMap<String, String> voteOptions = new HashMap<>();
        int i = 0;
        while (m.find()) {
            voteOptions.put(String.valueOf(i + 1), m.group().replaceFirst(GruBotPatterns.voteOptionTextOnly, "").replaceAll("\r", "").replaceAll("\n", ""));
            i++;
            Logger.log("Text match is found", Logger.INFO);
        }
        if (i == 0)
            Logger.log("Text match is not found", Logger.WARNING);

        Logger.log("Matching finished", Logger.INFO);
        Logger.log("Getting group users...", Logger.INFO);

        Map<String, Boolean> groupUsers = new HashMap<>();
        try {
            Query groupsQuery = db.collection("groups").whereEqualTo("chatId", chatId);
            ApiFuture<QuerySnapshot> snapshotApiFuture = groupsQuery.get();

            List<QueryDocumentSnapshot> documents = snapshotApiFuture.get().getDocuments();
            for (DocumentSnapshot document : documents) {
                groupUsers = (Map<String, Boolean>) document.get("users");
            }

        } catch (Exception e) {
            Logger.log(e.getMessage(), Logger.ERROR);
        }

        Logger.log("Creating poll...", Logger.INFO);
        HashMap<String, Object> vote = new HashMap<>();
        vote.put("group", message.getChatId());
        vote.put("messageId", -1);
        vote.put("groupName", message.getChat().getTitle());
        vote.put("author", message.getFrom().getId());
        vote.put("authorName", message.getFrom().getFirstName() + " " + message.getFrom().getLastName());
        vote.put("desc", voteTitle);
        vote.put("date", new Date());
        vote.put("type", "TELEGRAM");
        vote.put("voteOptions", voteOptions);
        HashMap<String, String> users = new HashMap<>();
        for (Map.Entry<String, Boolean> user : groupUsers.entrySet())
            users.put(user.getKey(), "new");
        vote.put("users", users);

        ApiFuture<DocumentReference> reference = db.collection("votes").add(vote);
        vote.put("reference", reference);
        Logger.log("Poll created", Logger.INFO);
        return vote;
    }

    public void setMessageIdToAction(int messageId, ApiFuture<DocumentReference> referenceApiFuture) throws ExecutionException, InterruptedException, NullPointerException {
        DocumentReference document = referenceApiFuture.get();
        Map<String, Object> updates = new HashMap<>();
        updates.put("messageId", messageId);

        document.update(updates);
    }

    @SuppressWarnings("unchecked")
    public EditMessageText updatePollAnswer(int userId, int pollOptionNumber, int pollMessageId) throws ExecutionException, InterruptedException, NullPointerException {
        EditMessageText editMessageText = null;

        Query pollQuery = db.collection("votes").whereEqualTo("messageId", pollMessageId);
        ApiFuture<QuerySnapshot> snapshotApiFuture = pollQuery.get();
        List<QueryDocumentSnapshot> documents = snapshotApiFuture.get().getDocuments();
        for (DocumentSnapshot document : documents) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("users." + userId, pollOptionNumber);

            document.getReference().update(updates);
        }

        pollQuery = db.collection("votes").whereEqualTo("messageId", pollMessageId);
        snapshotApiFuture = pollQuery.get();
        documents = snapshotApiFuture.get().getDocuments();
        for (DocumentSnapshot document : documents) {
            String newMessageText = "";
            Map<String, Object> updates = new HashMap<>();
            updates.put("users." + userId, pollOptionNumber);

            document.getReference().update(updates);

            editMessageText = getMessageText(document);
        }


        return editMessageText;
    }

    @SuppressWarnings("unchecked")
    private EditMessageText getMessageText(DocumentSnapshot document) {
        String title = (String) document.get("desc");
        HashMap<String, String> voteOptions = (HashMap<String, String>) document.get("voteOptions");
        HashMap<String, String> users = (HashMap<String, String>) document.get("users");

        String newMessageText = title;

        HashMap<String, Integer> voteCounts = new HashMap<>();

        for (Map.Entry<String, String> entry : users.entrySet()) {
            Integer currentValue = voteCounts.get(String.valueOf(entry.getValue()));
            if (currentValue == null)
                currentValue = 0;

            voteCounts.put(String.valueOf(entry.getValue()), currentValue + 1);
        }

        StringBuilder builder = new StringBuilder(newMessageText);
        for (Map.Entry<String, String> entry : voteOptions.entrySet()) {
            Integer value = voteCounts.get(entry.getKey().trim());
            if (value == null)
                value = 0;

            builder.append("\r\n")
                    .append(entry.getKey())
                    .append(". ")
                    .append(entry.getValue())
                    .append(" [")
                    .append(value)
                    .append("]");
        }
        Integer value = voteCounts.get("new");
        if (value == null)
            value = 0;
        builder.append("\r\n\r\n")
                .append("Не проголосовало [")
                .append(value)
                .append("]");

        newMessageText = builder.toString();

        return new EditMessageText()
                .setText(newMessageText)
                .setReplyMarkup(bot.getVoteKeyboard(voteOptions));
    }
}
