package com.example.tcpclient;

import android.content.DialogInterface;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

import chat.GroupChat;
import chat.GroupMember;

public class LocalStorage {
    public static List<GroupChat> currentUserGroupChats = new ArrayList<>();

    public static List<GroupChat> getCurrentUserGroupChats() {
        return currentUserGroupChats;
    }

    public static void setCurrentUserGroupChats(List<GroupChat> currentUserGroupChats) {
        LocalStorage.currentUserGroupChats = currentUserGroupChats;
    }
}
