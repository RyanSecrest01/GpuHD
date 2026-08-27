param(
	[string] $Source = (Join-Path $PSScriptRoot '..\runelite-client\src\main\resources\net\runelite\client\plugins\gpu\materials\authored_height_source.png'),
	[string] $Output = (Join-Path $PSScriptRoot '..\runelite-client\src\main\resources\net\runelite\client\plugins\gpu\materials')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$materials = @(
	@{ Name = 'grass'; Cell = 0; Normal = 1.15; Roughness = 224; Metallic = 0; Ao = 18 },
	@{ Name = 'dirt'; Cell = 1; Normal = 0.90; Roughness = 235; Metallic = 0; Ao = 14 },
	@{ Name = 'sand'; Cell = 2; Normal = 0.72; Roughness = 218; Metallic = 0; Ao = 10 },
	@{ Name = 'cobble'; Cell = 3; Normal = 1.05; Roughness = 202; Metallic = 0; Ao = 24 },
	@{ Name = 'masonry'; Cell = 4; Normal = 0.92; Roughness = 210; Metallic = 0; Ao = 20 },
	@{ Name = 'dock_wood'; Cell = 5; Normal = 0.82; Roughness = 192; Metallic = 0; Ao = 18 },
	@{ Name = 'painted_wood'; Cell = 6; Normal = 0.64; Roughness = 176; Metallic = 0; Ao = 13 },
	@{ Name = 'roof_tile'; Cell = 7; Normal = 0.88; Roughness = 196; Metallic = 0; Ao = 20 },
	@{ Name = 'metal'; Cell = 8; Normal = 0.48; Roughness = 112; Metallic = 255; Ao = 8 },
	@{ Name = 'foliage'; Cell = 9; Normal = 0.76; Roughness = 184; Metallic = 0; Ao = 16 }
)

$resolution = 128
$sourceImage = [System.Drawing.Bitmap]::new((Resolve-Path -LiteralPath $Source).Path)
New-Item -ItemType Directory -Path $Output -Force | Out-Null

function Get-HeightField($definition)
{
	$cellX = $definition.Cell % 4
	$cellY = [Math]::Floor($definition.Cell / 4)
	$x0 = [Math]::Floor($cellX * $sourceImage.Width / 4.0)
	$x1 = [Math]::Floor(($cellX + 1) * $sourceImage.Width / 4.0) - 1
	$y0 = [Math]::Floor($cellY * $sourceImage.Height / 3.0)
	$y1 = [Math]::Floor(($cellY + 1) * $sourceImage.Height / 3.0) - 1
	$field = New-Object 'double[,]' $resolution, $resolution
	for ($y = 0; $y -lt $resolution; ++$y)
	{
		$sourceY = $y0 + [Math]::Round($y * ($y1 - $y0) / ($resolution - 1.0))
		for ($x = 0; $x -lt $resolution; ++$x)
		{
			$sourceX = $x0 + [Math]::Round($x * ($x1 - $x0) / ($resolution - 1.0))
			$color = $sourceImage.GetPixel($sourceX, $sourceY)
			$field[$x, $y] = (0.2126 * $color.R + 0.7152 * $color.G + 0.0722 * $color.B) / 255.0
		}
	}
	return ,$field
}

function Write-Maps($definition, $height)
{
	$normal = [System.Drawing.Bitmap]::new($resolution, $resolution, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
	$properties = [System.Drawing.Bitmap]::new($resolution, $resolution, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
	for ($y = 0; $y -lt $resolution; ++$y)
	{
		$previousY = ($y + $resolution - 1) % $resolution
		$nextY = ($y + 1) % $resolution
		for ($x = 0; $x -lt $resolution; ++$x)
		{
			$previousX = ($x + $resolution - 1) % $resolution
			$nextX = ($x + 1) % $resolution
			$currentHeight = $height.GetValue($x, $y)
			$dx = ($height.GetValue($nextX, $y) - $height.GetValue($previousX, $y)) * $definition.Normal * 4.0
			$dy = ($height.GetValue($x, $nextY) - $height.GetValue($x, $previousY)) * $definition.Normal * 4.0
			$length = [Math]::Sqrt($dx * $dx + $dy * $dy + 1.0)
			$nx = [Math]::Round((-$dx / $length * 0.5 + 0.5) * 255.0)
			$ny = [Math]::Round((-$dy / $length * 0.5 + 0.5) * 255.0)
			$nz = [Math]::Round((1.0 / $length * 0.5 + 0.5) * 255.0)
			$normal.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $nx, $ny, $nz))

			$encodedHeight = [Math]::Max(0, [Math]::Min(255, [Math]::Round($currentHeight * 255.0)))
			$ao = [Math]::Max(0, 255 - [Math]::Round((1.0 - $currentHeight) * $definition.Ao))
			$properties.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($encodedHeight, $definition.Roughness, $definition.Metallic, $ao))
		}
	}
	$normal.Save((Join-Path $Output ($definition.Name + '_normal.png')), [System.Drawing.Imaging.ImageFormat]::Png)
	$properties.Save((Join-Path $Output ($definition.Name + '_properties.png')), [System.Drawing.Imaging.ImageFormat]::Png)
	$normal.Dispose()
	$properties.Dispose()
}

try
{
	foreach ($material in $materials)
	{
		Write-Maps $material (Get-HeightField $material)
	}
}
finally
{
	$sourceImage.Dispose()
}
