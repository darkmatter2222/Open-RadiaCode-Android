"""
Vega 2D Model - Uses Full Temporal Information

This model treats gamma spectra as 2D images (time × channels) and uses
Conv2d to extract both spectral and temporal features.

Input shape: (batch, 1, time_intervals, channels) = (B, 1, 60, 1023)
"""

import torch
import torch.nn as nn
from dataclasses import dataclass, field
from typing import List, Tuple


@dataclass
class Vega2DConfig:
    """Configuration for Vega 2D model."""
    
    # Input dimensions
    num_channels: int = 1023  # Energy channels
    num_time_intervals: int = 60  # Fixed time dimension
    
    # Output
    num_isotopes: int = 82
    
    # CNN architecture
    conv_channels: List[int] = field(default_factory=lambda: [32, 64, 128])
    kernel_size: Tuple[int, int] = (3, 7)  # (time, energy) - larger in energy dimension
    pool_size: Tuple[int, int] = (2, 2)
    
    # FC layers
    fc_hidden_dims: List[int] = field(default_factory=lambda: [512, 256])
    
    # Regularization
    dropout_rate: float = 0.3
    leaky_relu_slope: float = 0.01
    
    # Activity scaling
    max_activity_bq: float = 1000.0


class ConvBlock2D(nn.Module):
    """2D Convolutional block with BatchNorm, activation, pooling, and dropout."""
    
    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        kernel_size: Tuple[int, int],
        pool_size: Tuple[int, int],
        dropout_rate: float,
        leaky_relu_slope: float
    ):
        super().__init__()
        
        # Padding to maintain spatial dimensions before pooling
        padding = (kernel_size[0] // 2, kernel_size[1] // 2)
        
        self.conv1 = nn.Conv2d(in_channels, out_channels, kernel_size, padding=padding)
        self.bn1 = nn.BatchNorm2d(out_channels)
        self.conv2 = nn.Conv2d(out_channels, out_channels, kernel_size, padding=padding)
        self.bn2 = nn.BatchNorm2d(out_channels)
        self.activation = nn.LeakyReLU(leaky_relu_slope)
        self.pool = nn.MaxPool2d(pool_size)
        self.dropout = nn.Dropout2d(dropout_rate)
    
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.activation(self.bn1(self.conv1(x)))
        x = self.activation(self.bn2(self.conv2(x)))
        x = self.pool(x)
        x = self.dropout(x)
        return x


class Vega2DModel(nn.Module):
    """
    2D CNN model for gamma spectrum isotope identification.
    
    Treats spectra as images with time on one axis and energy channels on the other.
    This preserves temporal information that is lost in the 1D approach.
    """
    
    def __init__(self, config: Vega2DConfig = None):
        super().__init__()
        self.config = config or Vega2DConfig()
        
        # Build CNN backbone
        self.conv_blocks = nn.ModuleList()
        in_channels = 1  # Single channel input (like grayscale image)
        
        for out_channels in self.config.conv_channels:
            self.conv_blocks.append(ConvBlock2D(
                in_channels=in_channels,
                out_channels=out_channels,
                kernel_size=self.config.kernel_size,
                pool_size=self.config.pool_size,
                dropout_rate=self.config.dropout_rate,
                leaky_relu_slope=self.config.leaky_relu_slope
            ))
            in_channels = out_channels
        
        # Calculate flattened size after conv blocks
        self.flat_size = self._calculate_flat_size()
        
        # Fully connected classifier
        fc_layers = []
        fc_in = self.flat_size
        
        for fc_out in self.config.fc_hidden_dims:
            fc_layers.extend([
                nn.Linear(fc_in, fc_out),
                nn.BatchNorm1d(fc_out),
                nn.LeakyReLU(self.config.leaky_relu_slope),
                nn.Dropout(self.config.dropout_rate)
            ])
            fc_in = fc_out
        
        self.fc_backbone = nn.Sequential(*fc_layers)
        
        # Output heads
        self.classifier = nn.Linear(fc_in, self.config.num_isotopes)  # Logits for BCE
        self.regressor = nn.Sequential(
            nn.Linear(fc_in, self.config.num_isotopes),
            nn.ReLU()  # Activity must be non-negative
        )
        
        # Initialize weights
        self._init_weights()
    
    def _calculate_flat_size(self) -> int:
        """Calculate the flattened size after all conv blocks."""
        # Start with input dimensions
        h = self.config.num_time_intervals  # 60
        w = self.config.num_channels  # 1023
        
        # Each conv block applies pooling that halves dimensions
        for _ in self.config.conv_channels:
            h = h // self.config.pool_size[0]
            w = w // self.config.pool_size[1]
        
        # Final size = last_channels * h * w
        return self.config.conv_channels[-1] * h * w
    
    def _init_weights(self):
        """Initialize weights using Kaiming initialization."""
        for m in self.modules():
            if isinstance(m, (nn.Conv2d, nn.Linear)):
                nn.init.kaiming_normal_(m.weight, mode='fan_out', nonlinearity='leaky_relu')
                if m.bias is not None:
                    nn.init.constant_(m.bias, 0)
            elif isinstance(m, (nn.BatchNorm1d, nn.BatchNorm2d)):
                nn.init.constant_(m.weight, 1)
                nn.init.constant_(m.bias, 0)
    
    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        Forward pass.
        
        Args:
            x: Input tensor of shape (batch, 1, time_intervals, channels)
               or (batch, time_intervals, channels) - will add channel dim
        
        Returns:
            Tuple of (logits, activities):
                - logits: (batch, num_isotopes) - raw scores for BCE loss
                - activities: (batch, num_isotopes) - predicted activities (normalized 0-1)
        """
        # Add channel dimension if needed: (B, T, C) -> (B, 1, T, C)
        if x.dim() == 3:
            x = x.unsqueeze(1)
        
        # CNN backbone
        for conv_block in self.conv_blocks:
            x = conv_block(x)
        
        # Flatten
        x = x.view(x.size(0), -1)
        
        # FC backbone
        x = self.fc_backbone(x)
        
        # Output heads
        logits = self.classifier(x)
        activities = self.regressor(x)
        
        return logits, activities
    
    def predict(self, x: torch.Tensor, threshold: float = 0.5) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        Predict isotope presence and activities.
        
        Args:
            x: Input spectrum
            threshold: Probability threshold for presence
        
        Returns:
            Tuple of (presence, activities):
                - presence: (batch, num_isotopes) binary predictions
                - activities: (batch, num_isotopes) in Bq
        """
        logits, activities_norm = self.forward(x)
        probs = torch.sigmoid(logits)
        presence = (probs >= threshold).float()
        activities_bq = activities_norm * self.config.max_activity_bq
        return presence, activities_bq


def count_parameters(model: nn.Module) -> int:
    """Count trainable parameters."""
    return sum(p.numel() for p in model.parameters() if p.requires_grad)


if __name__ == "__main__":
    # Test the model
    config = Vega2DConfig()
    model = Vega2DModel(config)
    
    print(f"Vega 2D Model")
    print(f"  Input: ({config.num_time_intervals}, {config.num_channels})")
    print(f"  Conv channels: {config.conv_channels}")
    print(f"  FC dims: {config.fc_hidden_dims}")
    print(f"  Flat size: {model.flat_size}")
    print(f"  Parameters: {count_parameters(model):,}")
    
    # Test forward pass
    batch = torch.randn(4, 1, config.num_time_intervals, config.num_channels)
    logits, activities = model(batch)
    print(f"\n  Test batch: {batch.shape}")
    print(f"  Logits: {logits.shape}")
    print(f"  Activities: {activities.shape}")
