package com.twitch.frontend

import com.twitch.core.*

case class Model(
    status: Option[String] = None,
    user: Option[TwitchUser] = None,
    twitchClientId: Option[String] = None,
    searchQuery: String = "",
    searchResults: List[TwitchCategory] = Nil,
    selectedCategoryIds: Set[String] = Set.empty,
    followedCategories: List[TwitchCategory] = Nil,
    paginationCursor: Option[String] = None,
    currentPage: Int = 0,
    pageSize: Int = 5,
    notifications: List[StreamNotification] = Nil
)
