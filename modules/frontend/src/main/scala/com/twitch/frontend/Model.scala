package com.twitch.frontend

import com.twitch.core.*

case class SearchState(
  query: String = "",
  results: Vector[TwitchCategory] = Vector.empty,
  selectedCategoryIds: Set[String] = Set.empty,
  paginationCursor: Option[String] = None,
  currentPage: Int = 0,
  pageSize: Int = Defaults.SearchPageSize,
)

case class NotificationState(
  notifications: List[StreamNotification] = Nil,
)

case class TagFilterState(
  filters: List[TagFilter] = Nil,
  newIncludeTag: String = "",
  newExcludeTag: String = "",
)

case class IgnoredStreamerState(
  streamers: List[IgnoredStreamer] = Nil,
  searchQuery: String = "",
  searchResults: List[TwitchChannel] = Nil,
)

case class Model(
  status: Option[String] = None,
  user: Option[TwitchUser] = None,
  twitchClientId: Option[String] = None,
  followedCategories: List[TwitchCategory] = Nil,
  search: SearchState = SearchState(),
  notifications: NotificationState = NotificationState(),
  tagFilters: TagFilterState = TagFilterState(),
  ignoredStreamers: IgnoredStreamerState = IgnoredStreamerState(),
  topGameIds: Set[String] = Set.empty,
  pendingPopularFollow: Option[TwitchCategory] = None,
)
