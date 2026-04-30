package com.twitch.frontend

import com.twitch.core.*

case class SearchState(
  currentPage: Int = 0,
  pageSize: Int = Defaults.SearchPageSize,
  paginationCursor: Option[String] = None,
  query: String = "",
  results: Vector[TwitchCategory] = Vector.empty,
  selectedCategoryIds: Set[String] = Set.empty,
)

case class NotificationState(
  notifications: List[StreamNotification] = Nil,
)

case class TagFilterState(
  filters: List[TagFilter] = Nil,
  newExcludeTag: String = "",
  newIncludeTag: String = "",
)

case class IgnoredStreamerState(
  searchQuery: String = "",
  searchResults: List[TwitchChannel] = Nil,
  streamers: List[IgnoredStreamer] = Nil,
)

case class Model(
  followedCategories: List[TwitchCategory] = Nil,
  ignoredStreamers: IgnoredStreamerState = IgnoredStreamerState(),
  notifications: NotificationState = NotificationState(),
  pendingPopularFollow: Option[TwitchCategory] = None,
  search: SearchState = SearchState(),
  status: Option[String] = None,
  tagFilters: TagFilterState = TagFilterState(),
  topGameIds: Set[String] = Set.empty,
  twitchClientId: Option[String] = None,
  user: Option[TwitchUser] = None,
)
