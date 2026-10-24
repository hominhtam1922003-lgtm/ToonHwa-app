# ToonHwa — App đọc Manhwa (Android, Kotlin)

App đọc truyện manhwa lấy dữ liệu trực tiếp từ **NetTruyen** (nettruyen.gg,
dự phòng nettruyenar.com — cùng một kho, app tự dò cửa đang sống qua
`config.json`), build APK hoàn toàn bằng GitHub Actions.

Tính năng:
- Khoá thể loại Manhwa: Trang chủ (mới cập nhật) / Truyện mới / Top xem nhiều,
  tìm kiếm, yêu thích, lịch sử đọc, thông báo chap mới cho truyện yêu thích.
- Danh sách chương đầy đủ qua dịch vụ ChapterList của chính web.
- **Tăng nét ảnh bằng AI ngay trên máy** (waifu2x CUNet 2x, ONNX Runtime):
  ảnh gốc hiện ngay, bản nét xử lý ở nền rồi tự thay vào + lưu cache;
  có công tắc "AI nét" trong màn đọc.
- Đổi tên miền nguồn chỉ cần sửa `config.json` ở thư mục gốc repo.

Cách build: mở tab **Actions** → tạo workflow từ file `WORKFLOW-PASTE.txt`
(dán nội dung vào trình soạn workflow) → commit là bản build chạy.
