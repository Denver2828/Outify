use super::*;
use std::{
    future::Future,
    task::{Context, Poll, Waker},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
    sync::oneshot,
};

struct ResponseFixture {
    url: String,
    received: oneshot::Receiver<()>,
    headers: oneshot::Sender<()>,
    body: oneshot::Sender<()>,
    task: tokio::task::JoinHandle<()>,
}

async fn response_fixture(status: u16, retry_after: &str) -> ResponseFixture {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let headers = format!(
        "HTTP/1.1 {status} Test\r\n{retry_after}Content-Length: 4\r\nConnection: close\r\n\r\n"
    );
    let (received_tx, received) = oneshot::channel();
    let (headers_tx, headers_rx) = oneshot::channel();
    let (body, body_rx) = oneshot::channel();
    let task = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        while !request.ends_with(b"\r\n\r\n") {
            let byte = stream.read_u8().await.unwrap();
            request.push(byte);
        }
        received_tx.send(()).unwrap();
        headers_rx.await.unwrap();
        stream.write_all(headers.as_bytes()).await.unwrap();
        body_rx.await.unwrap();
        stream.write_all(b"body").await.unwrap();
    });
    ResponseFixture {
        url,
        received,
        headers: headers_tx,
        body,
        task,
    }
}

fn run(test: impl Future<Output = ()>) {
    struct ResetWindow;
    impl Drop for ResetWindow {
        fn drop(&mut self) {
            RATE_LIMIT_UNTIL_MS.store(0, Ordering::Release);
        }
    }
    let _reset = ResetWindow;
    RATE_LIMIT_UNTIL_MS.store(0, Ordering::Release);
    tokio::runtime::Runtime::new().unwrap().block_on(async {
        tokio::time::timeout(Duration::from_secs(10), test)
            .await
            .unwrap();
    });
}

#[test]
fn queued_dispatch_stops_at_headers_without_waiting_for_body() {
    run(async {
        let fixture = response_fixture(429, "Retry-After: 60\r\n").await;
        let client = Client::new();
        let first = tokio::spawn(client.get(&fixture.url).send_gated("first", false));
        fixture.received.await.unwrap();
        let mut queued = Box::pin(client.get(&fixture.url).send_gated("queued", false));
        assert!(matches!(
            queued
                .as_mut()
                .poll(&mut Context::from_waker(Waker::noop())),
            Poll::Pending
        ));
        fixture.headers.send(()).unwrap();
        let response = first.await.unwrap().unwrap();
        let deadline = rate_limit_until_ms();
        assert!(deadline > now_ms());
        assert!(matches!(
            queued.await,
            Err(SpotifyApiError::RateLimited { .. })
        ));

        let mut body_result = Box::pin(ensure_success("first", response));
        assert!(matches!(
            body_result
                .as_mut()
                .poll(&mut Context::from_waker(Waker::noop())),
            Poll::Pending
        ));
        // The error body cannot extend the window again after header admission released it.
        fixture.body.send(()).unwrap();
        assert!(matches!(
            body_result.await,
            Err(SpotifyApiError::RateLimited {
                retry_after_secs: 60,
                ..
            })
        ));
        assert_eq!(RATE_LIMIT_UNTIL_MS.load(Ordering::Acquire), deadline);
        fixture.task.await.unwrap();
    });
}

#[test]
fn dispatch_rechecks_after_token_wait_or_unauthorized_response() {
    run(async {
        let client = Client::new();
        let token_lock = tokio::sync::Mutex::new(());
        let token_guard = token_lock.lock().await;
        check_rate_limit("before_token_wait").unwrap();
        let mut waiting = Box::pin(async {
            let _token = token_lock.lock().await;
            client
                .get("http://127.0.0.1:1")
                .send_gated("after_token_wait", false)
                .await
        });
        assert!(matches!(
            waiting
                .as_mut()
                .poll(&mut Context::from_waker(Waker::noop())),
            Poll::Pending
        ));

        let fixture = response_fixture(401, "").await;
        let first = tokio::spawn(client.get(&fixture.url).send_gated("get_devices", false));
        fixture.received.await.unwrap();
        fixture.headers.send(()).unwrap();
        let unauthorized = first.await.unwrap().unwrap();
        assert_eq!(unauthorized.status(), reqwest::StatusCode::UNAUTHORIZED);
        note_rate_limited(60);
        drop(token_guard);
        assert!(matches!(
            waiting.await,
            Err(SpotifyApiError::RateLimited { .. })
        ));
        assert!(matches!(
            client
                .get(&fixture.url)
                .send_gated("get_devices", false)
                .await,
            Err(SpotifyApiError::RateLimited { .. })
        ));
        fixture.body.send(()).unwrap();
        fixture.task.await.unwrap();
    });
}

#[test]
fn probe_bypasses_admission_but_refresh_does_not_and_errors_release_lock() {
    run(async {
        let client = Client::new();
        note_rate_limited(1);
        let fixture = response_fixture(429, "Retry-After: invalid\r\n").await;
        let probe = tokio::spawn(client.get(&fixture.url).send_gated("probe", true));
        fixture.received.await.unwrap();
        fixture.headers.send(()).unwrap();
        let response = probe.await.unwrap().unwrap();
        assert_eq!(retry_after_secs(&response), DEFAULT_RETRY_AFTER_SECS);
        assert!(rate_limit_until_ms() >= now_ms() + 20_000);
        assert!(matches!(
            client
                .post(&fixture.url)
                .send_gated("refresh_token", false)
                .await,
            Err(SpotifyApiError::RateLimited { .. })
        ));
        fixture.body.send(()).unwrap();
        fixture.task.await.unwrap();

        RATE_LIMIT_UNTIL_MS.store(now_ms() - 1, Ordering::Release);
        assert!(matches!(
            client.get("invalid-url").send_gated("invalid", false).await,
            Err(SpotifyApiError::Reqwest(_))
        ));
        let success = response_fixture(200, "").await;
        let request = tokio::spawn(client.get(&success.url).send_gated("success", false));
        success.received.await.unwrap();
        success.headers.send(()).unwrap();
        let response = ensure_success("success", request.await.unwrap().unwrap())
            .await
            .unwrap();
        success.body.send(()).unwrap();
        assert_eq!(response.text().await.unwrap(), "body");
        success.task.await.unwrap();
    });
}

#[test]
fn timeout_releases_admission_when_refresh_headers_never_arrive() {
    run(async {
        let fixture = response_fixture(200, "").await;
        let request = tokio::spawn(
            Client::new()
                .post(&fixture.url)
                .timeout(REQUEST_TIMEOUT)
                .send_gated("refresh_token", false),
        );
        fixture.received.await.unwrap();
        // Keep the socket open without headers: this must be a timeout, not a disconnect.
        assert!(matches!(
            request.await.unwrap(),
            Err(SpotifyApiError::Reqwest(error)) if error.is_timeout()
        ));
        let _admission = REQUEST_ADMISSION
            .try_lock()
            .expect("timed-out transport must release admission");
        fixture.task.abort();
        assert!(fixture.task.await.unwrap_err().is_cancelled());
    });
}
