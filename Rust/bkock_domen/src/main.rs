use axum::{http::StatusCode, response::Html, Router};
use std::net::SocketAddr;

const BLOCK_HTML: &str = include_str!("blocked.html");

async fn block_page() -> (StatusCode, Html<&'static str>) {
    (StatusCode::FORBIDDEN, Html(BLOCK_HTML))
}

#[tokio::main]
async fn main() {
    // Если хочешь 80 — поставь ":80" (но в Termux без root чаще всего не даст)
    let addr: SocketAddr = "0.0.0.0:8080".parse().unwrap();

    // fallback = обработчик для всего, что не совпало с роутами.
    // А так как роутов нет — он сработает на ВСЁ.
    let app = Router::new().fallback(block_page);

    println!("Listening on http://{addr}");
    axum::serve(tokio::net::TcpListener::bind(addr).await.unwrap(), app)
        .await
        .unwrap();
}
