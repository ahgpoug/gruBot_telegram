package gruBot.telegram.bot;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import gruBot.telegram.firestore.Firestore;
import gruBot.telegram.logger.Logger;
import org.telegram.telegrambots.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.api.objects.ChatMember;
import org.telegram.telegrambots.api.objects.Message;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GruBot extends TelegramLongPollingBot {
    private Firestore firestore;

    @Override
    public String getBotUsername() {
        return GruBotConfig.BOT_USERNAME;
    }

    @Override
    public String getBotToken() {
        return GruBotConfig.BOT_TOKEN;
    }

    public GruBot() {
        super();
        Logger.log("Initializing Firestore...", Logger.INFO);
        this.firestore = new Firestore(this);
        Logger.log("Started", Logger.INFO);
    }

    public GruBot(DefaultBotOptions options) {
        super(options);
        Logger.log("Initializing Firestore...", Logger.INFO);
        this.firestore = new Firestore(this);
        Logger.log("Started", Logger.INFO);
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat())) {
            Message message = update.getMessage();
            try {
                processCommonMessage(message);

                if (!firestore.checkGroupExists(message.getChatId()))
                    firestore.createNewGroup(update);

                firestore.checkUserExistsInGroup(update);

                if (message.hasText()) {
                    Matcher m = Pattern.compile(GruBotPatterns.announcement, Pattern.DOTALL).matcher(message.getText());
                    if (m.matches()) {
                        if (canUserCreateActions(message))
                            processAnnouncement(update);
                        else
                            sendTextMessage(update, "У пользователя недостаточно прав для создания объявлений");
                    }

                    m = Pattern.compile(GruBotPatterns.vote, Pattern.DOTALL).matcher(message.getText());
                    if (m.matches()) {
                        if (canUserCreateActions(message))
                            processVote(update);
                        else
                            sendTextMessage(update, "У пользователя недостаточно прав для создания голосований");
                    }

                    m = Pattern.compile(GruBotPatterns.article, Pattern.DOTALL).matcher(message.getText());
                    if (m.matches()) {
                        if (canUserCreateActions(message))
                            processArticle(update);
                        else
                            sendTextMessage(update, "У пользователя недостаточно прав для создания статей");
                    }
                }
            } catch (Exception e) {
                Logger.log(e.getMessage(), Logger.ERROR);
            }
        } else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            Message message = update.getCallbackQuery().getMessage();

            if (callbackData.contains("update_poll_")) {
                int checkedIndex = Integer.valueOf(callbackData.substring(callbackData.lastIndexOf("_") + 1));

                try {
                    EditMessageText editMessageText = firestore.updatePollAnswer(update.getCallbackQuery().getFrom().getId(), checkedIndex, message.getMessageId());
                    editMessageText.setChatId(message.getChatId())
                            .setMessageId(message.getMessageId());

                    execute(editMessageText);
                } catch (Exception e) {
                    Logger.log(e.getMessage(), Logger.ERROR);
                }
            }
        }
    }

    public void updatePoll(EditMessageText editMessageText) {
        try {
            execute(editMessageText);
        } catch (Exception e) {
            Logger.log(e.getMessage(), Logger.ERROR);
        }
    }

    private void processCommonMessage(Message message) {
        String chatName = message.getChat().getTitle();
        String messageText = message.getText();
        String messageAuthor = message.getFrom().getUserName();

        String result = String.format("'%s' wrote to '%s': '%s'", messageAuthor, chatName, messageText);
        Logger.log(result, Logger.INFO);
    }

    @SuppressWarnings("unchecked")
    private void processArticle(Update update) throws TelegramApiException {
        Message message = update.getMessage();
        Logger.log("Article is detected", Logger.INFO);
        HashMap<String, Object> article = firestore.createNewArticle(update);
        String announcementText = String.format("Статья:\r\n%s\r%s", article.get("desc").toString(), article.get("text").toString());

        Message articleMessage = sendTextMessage(update, announcementText);

        if (message.getChat().isGroupChat())
            sendTextMessage(update, "Закреплять сообщения можно только в супер-чатах.\nИзмените группу для активации данного функционала");
        else {
            PinChatMessage pinChatMessage = new PinChatMessage()
                    .setChatId(message.getChatId())
                    .setMessageId(articleMessage.getMessageId());
            execute(pinChatMessage);
        }

        try {
            firestore.setMessageIdToAction(articleMessage.getMessageId(), (ApiFuture<DocumentReference>) article.get("reference"));
        } catch (Exception e) {
            Logger.log(e.getMessage(), Logger.ERROR);
        }
    }

    @SuppressWarnings("unchecked")
    private void processAnnouncement(Update update) throws TelegramApiException {
        Message message = update.getMessage();
        Logger.log("Announcement is detected", Logger.INFO);
        HashMap<String, Object> announcement = firestore.createNewAnnouncement(update);
        String announcementText = String.format("Объявление:\r\n%s\r%s", announcement.get("desc").toString(), announcement.get("text").toString());

        Message announcementMessage = sendTextMessage(update, announcementText);

        if (message.getChat().isGroupChat())
            sendTextMessage(update, "Закреплять сообщения можно только в супер-чатах.\nИзмените группу для активации данного функционала");
        else {
            PinChatMessage pinChatMessage = new PinChatMessage()
                    .setChatId(message.getChatId())
                    .setMessageId(announcementMessage.getMessageId());
            execute(pinChatMessage);
        }

        try {
            firestore.setMessageIdToAction(announcementMessage.getMessageId(), (ApiFuture<DocumentReference>) announcement.get("reference"));
        } catch (Exception e) {
            Logger.log(e.getMessage(), Logger.ERROR);
        }
    }

    @SuppressWarnings("unchecked")
    private void processVote(Update update) throws TelegramApiException {
        Message message = update.getMessage();
        Logger.log("Vote is detected", Logger.INFO);

        HashMap<String, Object> vote = firestore.createNewPoll(update);

        StringBuilder options = new StringBuilder();
        for (Map.Entry<String, String> option : ((HashMap<String, String>) vote.get("voteOptions")).entrySet())
            options.append(option.getKey()).append(". ").append(option.getValue()).append(" [0]").append("\r\n");
        options.append("\r\n").append("Не проголосовало [").append(((HashMap<String, String>) vote.get("users")).size()).append("]");

        String announcementText = String.format("Голосование:\r\n%s\r\n%s", vote.get("desc").toString(), options);

        SendMessage sendVoteMessage = new SendMessage()
                .setChatId(message.getChatId())
                .setText(announcementText)
                .setReplyMarkup(getVoteKeyboard((HashMap<String, String>) vote.get("voteOptions")));
        Message voteMessage = execute(sendVoteMessage);

        if (message.getChat().isGroupChat())
            sendTextMessage(update, "Закреплять сообщения можно только в супер-чатах.\nИзмените группу для активации данного функционала");
        else {
            PinChatMessage pinChatMessage = new PinChatMessage()
                    .setChatId(message.getChatId())
                    .setMessageId(voteMessage.getMessageId());
            execute(pinChatMessage);
        }

        try {
            firestore.setMessageIdToAction(voteMessage.getMessageId(), (ApiFuture<DocumentReference>) vote.get("reference"));
        } catch (Exception e) {
            Logger.log(e.getMessage(), Logger.ERROR);
        }
    }

    private Message sendTextMessage(Update update, String text) throws TelegramApiException {
        SendMessage sendMessage = new SendMessage()
                .setText(text)
                .setChatId(update.getMessage().getChatId());

        return execute(sendMessage);
    }

    public InlineKeyboardMarkup getVoteKeyboard(HashMap<String, String> options) {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        for (Map.Entry<String , String> option : options.entrySet()) {
            ArrayList<InlineKeyboardButton> buttons = new ArrayList<>();
            InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
            String itemNumber = option.getKey();
            inlineKeyboardButton.setText(itemNumber).setCallbackData("update_poll_" + itemNumber);
            buttons.add(inlineKeyboardButton);
            rowsInline.add(buttons);
        }
        markupInline.setKeyboard(rowsInline);
        return markupInline;
    }

    private boolean canUserCreateActions(Message message) {
        try {
            GetChatMember getChatMember = new GetChatMember()
                    .setUserId(message.getFrom().getId())
                    .setChatId(message.getChatId());

            ChatMember chatMember = execute(getChatMember);
            if (chatMember != null) {
                String memberStatus = chatMember.getStatus();
                return memberStatus.equals("creator") || memberStatus.equals("administrator");
            }

            return false;
        } catch (Exception e) {
            Logger.log(e.getMessage(), Logger.ERROR);
            e.printStackTrace();
            return false;
        }
    }
}
