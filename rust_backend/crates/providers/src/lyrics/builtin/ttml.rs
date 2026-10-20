//! Keyless services that return Apple Music's TTML documents directly.
use super::*;
use crate::lyrics::http;
use mornye_core::lyrics::payloads;
use serde::Deserialize;

#[derive(Default, Deserialize)]
#[serde(default)]
struct LrcRedSearch {
    hits: Vec<LrcRedHit>,
}

#[derive(Default, Deserialize)]
#[serde(default)]
struct LrcRedHit {
    isrc: String,
    title: String,
    artist: String,
    duration: f64,
}

#[derive(Default, Deserialize)]
#[serde(default)]
struct BetterLyricsResponse {
    ttml: String,
}

/// Only an ISRC is ever placed in the document path.
fn isrc_path(isrc: &str) -> Option<String> {
    let isrc = isrc.trim().to_ascii_uppercase();
    (isrc.len() == 12 && isrc.bytes().all(|byte| byte.is_ascii_alphanumeric())).then_some(isrc)
}

impl BuiltinLyricsClient {
    pub(super) fn lrc_red(
        &self,
        request: &SearchRequest,
        check: &Check<'_>,
    ) -> Result<LyricsResponse, LyricsError> {
        let query = format!("{} {}", request.track, request.artist);
        if query.trim().is_empty() {
            return Err(LyricsError::NotFound("empty search query".into()));
        }
        let mut search = Request::new("https://lrc.red/search.json");
        search.params = pairs(&[("q", query.trim())]);
        search.unavailable_errors = true;
        let response: LrcRedSearch =
            http::decode(&self.http.get(search, check)?.body, true, check)?;
        let hit = select(&response.hits, request, |hit| {
            (
                hit.title.clone(),
                hit.artist.clone(),
                hit.duration,
                isrc_path(&hit.isrc).is_some()
                    && matches(&hit.title, &hit.artist, hit.duration, request),
            )
        })
        .ok_or_else(|| LyricsError::NotFound("no matching song found on lrc.red".into()))?;
        let isrc = isrc_path(&hit.isrc).unwrap_or_default();
        let endpoint = format!("https://lrc.red/s/{isrc}.ttml");
        let mut document = Request::new(&endpoint);
        document.headers = pairs(&[("Accept", "application/ttml+xml, application/xml")]);
        document.allowed = &[200, 404];
        document.unavailable_errors = true;
        let response = self.http.get(document, check)?;
        if response.status == 404 {
            return Err(LyricsError::NotFound(
                "lrc.red has no lyrics for this song".into(),
            ));
        }
        self.ttml(&text_from_bytes(&response.body), "lrc.red", request)
    }

    pub(super) fn better_lyrics(
        &self,
        request: &SearchRequest,
        check: &Check<'_>,
    ) -> Result<LyricsResponse, LyricsError> {
        if request.track.trim().is_empty() || request.artist.trim().is_empty() {
            return Err(LyricsError::NotFound("empty search query".into()));
        }
        let mut get = Request::new("https://lyrics-api.boidu.dev/getLyrics");
        get.params = pairs(&[("s", request.track.trim()), ("a", request.artist.trim())]);
        let seconds = request.duration.round();
        if seconds.is_finite() && seconds > 0.0 {
            get.params.insert("d".into(), (seconds as i64).to_string());
        }
        // Without a key the service answers only songs already in its cache,
        // and asks for a key otherwise. That is a miss, not an outage.
        get.allowed = &[200, 401, 404];
        get.unavailable_errors = true;
        let response = self.http.get(get, check)?;
        if response.status != 200 {
            return Err(LyricsError::NotFound(
                "betterlyrics has no cached lyrics".into(),
            ));
        }
        let payload: BetterLyricsResponse = http::decode(&response.body, true, check)?;
        if payload.ttml.trim().is_empty() {
            return Err(LyricsError::NotFound(
                "betterlyrics returned no lyrics".into(),
            ));
        }
        self.ttml(&payload.ttml, "BetterLyrics", request)
    }

    fn ttml(
        &self,
        raw: &str,
        source: &str,
        request: &SearchRequest,
    ) -> Result<LyricsResponse, LyricsError> {
        let lyrics = payloads::ttml_lyrics(
            raw,
            source,
            source,
            request.options.multi_person_word_by_word,
            request.options.apple_elrc_word_sync,
        )
        .map_err(LyricsError::Unavailable)?;
        if lyrics.has_usable_text() {
            Ok(lyrics)
        } else {
            Err(LyricsError::NotFound(format!(
                "no lyrics found on {source}"
            )))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Resolver;
    impl TrackResolver for Resolver {
        fn deezer_id_from_spotify(&self, _: &str, _: &Check<'_>) -> Result<String, LyricsError> {
            unreachable!("ttml providers do not resolve tracks")
        }
    }

    #[test]
    fn only_isrcs_reach_the_document_path() {
        assert_eq!(isrc_path(" usug11904206 ").as_deref(), Some("USUG11904206"));
        for invalid in [
            "",
            "USUG1190420",
            "USUG119042067",
            "../../secret",
            "USUG1190420/",
        ] {
            assert_eq!(isrc_path(invalid), None, "{invalid}");
        }
    }

    #[test]
    #[ignore = "requires live lrc.red and BetterLyrics access"]
    fn live_ttml_providers_return_synced_apple_lyrics() {
        let network = NetworkService::new().unwrap();
        let client = BuiltinLyricsClient::new(&network, "", Arc::new(Resolver));
        let request = SearchRequest {
            track: "Blinding Lights".into(),
            artist: "The Weeknd".into(),
            duration: 200.0,
            ..SearchRequest::default()
        };
        for (lyrics, source) in [
            (client.lrc_red(&request, &|| Ok(())).unwrap(), "lrc.red"),
            (
                client.better_lyrics(&request, &|| Ok(())).unwrap(),
                "BetterLyrics",
            ),
        ] {
            assert!(lyrics.is_synced(), "{source}");
            assert_eq!(lyrics.provider, source);
            assert_eq!(lyrics.source, source);
        }
    }
}
